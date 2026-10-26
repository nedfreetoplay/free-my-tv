package io.freemytv;

import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The weather from yandex.ru/pogoda, the page the site renders for a browser: the "Сейчас" block for the current
 * temperature and sky, and the hourly strip for the next hours.
 *
 * Open-Meteo (what this app used before) is unreachable on Russian ISPs: api.open-meteo.com times out from the TV
 * while every other host answers, and Yandex's own JSON API (api.weather.yandex.ru) needs a key. The page itself is
 * served without one, so this reads it: the numbers sit in elements whose class names start with the block name
 * (AppFactTemperature_value, AppHourlyItem_main…), and the sky is the Russian word from the hidden text for readers.
 *
 * The page writes its clock times in the timezone it guesses from the viewer's address (Moscow, for a TV), so the
 * hours are taken from their "…Z" timestamps instead, and sunrise/sunset are computed from the coordinates.
 */
final class YandexWeather {
    private YandexWeather() {}

    /** The city page by coordinates: no transliterated slug needed, and the language is the one we parse. */
    static String url(String latitude, String longitude) {
        return "https://yandex.ru/pogoda/ru?lat=" + latitude + "&lon=" + longitude;
    }

    private static final Pattern SIGN = Pattern.compile("AppFactTemperature_sign[^\"]*\"[^>]*>([^<]*)<");
    private static final Pattern VALUE = Pattern.compile("AppFactTemperature_value[^\"]*\"[^>]*>([^<]*)<");
    private static final Pattern FACT_SKY = Pattern.compile("погода сейчас:\\s*([^,<]{1,60})");
    private static final Pattern HOUR_ITEM = Pattern.compile("AppHourlyItem_container__[^\"]*\".*?</li>", Pattern.DOTALL);
    /** The clock in the strip is the city's own time (the page writes "09:00" for the 9th hour there, not the viewer's). */
    private static final Pattern HOUR_TIME = Pattern.compile("<time[^>]*>([^<]+)</time>");
    private static final Pattern HOUR_TEMP = Pattern.compile("AppHourlyItem_main__[^\"]*\"[^>]*>([^<]+)<");
    private static final Pattern HOUR_SKY = Pattern.compile("°,\\s*([^,<]{1,40}),");
    private static final Pattern SHORT_TEMP = Pattern.compile("AppShortForecastDay_temperature__[^\"]*\"[^>]*>([^<]+)<");
    private static final Pattern NUMBER = Pattern.compile("(\\d+)");

    static HomeData.Weather parse(String html, String latitude, String longitude) {
        HomeData.Weather w = new HomeData.Weather();

        Matcher value = VALUE.matcher(html);
        if (value.find()) {
            Matcher sign = SIGN.matcher(html);
            double t = number(value.group(1));
            // The page writes minus as U+2212, not as the ASCII hyphen.
            if (sign.find() && sign.group(1).indexOf('\u2212') >= 0) t = -t;
            if (!Double.isNaN(t)) w.temp = t;
        }

        // Today's high and low: the first card of the ten-day strip ("Сегодня": the day's high, then the night's low).
        int card = html.indexOf("AppShortForecastDay_container__");
        if (card >= 0) {
            Matcher high = SHORT_TEMP.matcher(html.substring(card, Math.min(html.length(), card + 1500)));
            if (high.find()) w.max = number(high.group(1));
            if (high.find()) w.min = number(high.group(1));
        }

        long[] light = sun(latitude, longitude);
        w.sunrise = light[0];
        w.sunset = light[1];
        boolean lit = w.sunrise > 0 && w.sunset > w.sunrise;

        Matcher sky = FACT_SKY.matcher(html);
        w.code = sky.find() ? code(sky.group(1)) : -1;

        long now = System.currentTimeMillis();
        w.isDay = !lit || (now >= w.sunrise && now < w.sunset);

        Matcher item = HOUR_ITEM.matcher(html);
        while (item.find() && w.hours.size() < 6) {
            String hour = item.group();
            Matcher time = HOUR_TIME.matcher(hour), temp = HOUR_TEMP.matcher(hour);
            if (!time.find() || !temp.find()) continue;
            long at = clock(time.group(1), now);
            if (at <= now) continue;
            double t = number(temp.group(1));
            if (Double.isNaN(t)) continue;
            Matcher s = HOUR_SKY.matcher(hour);
            w.hours.add(at);
            w.hourTemp.add(t);
            w.hourCode.add(s.find() ? code(s.group(1)) : 0);
            w.hourDay.add(!lit || (at >= w.sunrise && at < w.sunset));
        }
        if (w.code < 0) w.code = w.hourCode.isEmpty() ? 0 : w.hourCode.get(0);
        return w;
    }

    /**
     * Sunrise and sunset today at those coordinates, in epoch ms: NOAA's solar position, a minute either way.
     * {0, 0} above the polar circles, when the sun doesn't rise or doesn't set.
     */
    private static long[] sun(String latitude, String longitude) {
        double lat, lon;
        try {
            lat = Double.parseDouble(latitude);
            lon = Double.parseDouble(longitude);
        } catch (NumberFormatException e) {
            return new long[] {0, 0};
        }
        long midnight = utcMidnight();
        double g = 2 * Math.PI / 365 * (utcDayOfYear() - 1);
        double eq = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g)
                - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g));
        double decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g) - 0.006758 * Math.cos(2 * g)
                + 0.000907 * Math.sin(2 * g) - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g);
        double cosHa = Math.cos(Math.toRadians(90.833)) / (Math.cos(Math.toRadians(lat)) * Math.cos(decl))
                - Math.tan(Math.toRadians(lat)) * Math.tan(decl);
        if (cosHa > 1 || cosHa < -1) return new long[] {0, 0};
        double ha = Math.toDegrees(Math.acos(cosHa));
        long rise = Math.round((720 - 4 * (lon + ha) - eq) * 60_000L);
        long set = Math.round((720 - 4 * (lon - ha) - eq) * 60_000L);
        return new long[] {midnight + rise, midnight + set};
    }

    /** Midnight of today in UTC. */
    private static long utcMidnight() {
        java.util.Calendar c = utcNow();
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static int utcDayOfYear() {
        return utcNow().get(java.util.Calendar.DAY_OF_YEAR);
    }

    private static java.util.Calendar utcNow() {
        return java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }

    /** "09:00" on the TV's own date and zone; a clock well in the past means the strip crossed midnight. */
    private static long clock(String hhmm, long now) {
        Matcher m = NUMBER.matcher(hhmm);
        if (!m.find()) return 0;
        int hour = Integer.parseInt(m.group(1));
        if (!m.find()) return 0;
        int minute = Integer.parseInt(m.group(1));
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, hour);
        c.set(java.util.Calendar.MINUTE, minute);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        long at = c.getTimeInMillis();
        return at < now - 12 * 3_600_000L ? at + 24 * 3_600_000L : at;
    }

    /** "−3°", "+3°", "0°": the page's minus is U+2212. */
    private static double number(String text) {
        String s = text.replace('\u2212', '-').replace("°", "").replace("+", "").trim();
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** The sky as a WMO code, so the home screen and the screensaver keep their own symbols and words. */
    private static int code(String sky) {
        String s = sky.toLowerCase(Locale.ROOT);
        if (s.contains("гроза") || s.contains("грозы")) return 95;
        if (s.contains("град")) return 96;
        if (s.contains("морос")) return 51;
        // Sleet ("снег с дождём", "дождь со снегом") draws as snow: the app has no separate word for it, and at
        // these temperatures the snowflake is the closer symbol (68 would fall through to "Rain").
        if (s.contains("мокрый снег") || s.contains("снег с дожд") || s.contains("дождь со снег")) return 73;
        if (s.contains("сильный снег") || s.contains("снегопад")) return 75;
        if (s.contains("небольшой снег")) return 71;
        if (s.contains("снег")) return 73;
        if (s.contains("ливень") || s.contains("сильный дождь")) return 82;
        if (s.contains("небольшой дождь")) return 61;
        if (s.contains("дождь")) return 63;
        if (s.contains("туман") || s.contains("дымк")) return 45;
        if (s.contains("малооблачно")) return 1;
        if (s.contains("облачно с прояснениями") || s.contains("переменная облачность")) return 2;
        if (s.contains("облачно") || s.contains("пасмурно")) return 3;
        if (s.contains("ясно")) return 0;
        return -1;
    }
}
