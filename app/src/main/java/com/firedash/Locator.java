package com.firedash;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds where the tablet is. Fire tablets have no GPS, so this uses Wi-Fi location
 * (Settings > Location-Based Services must be on). If that's off or fails, it falls back
 * to a rough guess from the internet connection.
 */
final class Locator {
    interface Callback {
        void found(double lat, double lon, String name, boolean precise);
        void failed(String why);
    }

    private Locator() {}

    static void find(final Context ctx, final Callback cb) {
        final Handler main = new Handler(Looper.getMainLooper());
        final LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        Location best = null;
        boolean networkOn = false;
        if (lm != null) {
            try {
                for (String provider : lm.getProviders(true)) {
                    if (LocationManager.NETWORK_PROVIDER.equals(provider)) networkOn = true;
                    Location l = lm.getLastKnownLocation(provider);
                    if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                }
            } catch (SecurityException ignored) { }
        }
        if (best != null && System.currentTimeMillis() - best.getTime() < 30 * 60000L) {
            name(ctx, best.getLatitude(), best.getLongitude(), true, cb, main);
            return;
        }
        if (lm == null || !networkOn) {
            ipFallback(ctx, cb, main, best);
            return;
        }
        final Location stale = best;
        final boolean[] done = { false };
        final LocationListener listener = new LocationListener() {
            @Override public void onLocationChanged(Location l) {
                if (done[0]) return;
                done[0] = true;
                try { lm.removeUpdates(this); } catch (SecurityException ignored) { }
                name(ctx, l.getLatitude(), l.getLongitude(), true, cb, main);
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
            @Override public void onProviderEnabled(String provider) { }
            @Override public void onProviderDisabled(String provider) { }
        };
        try {
            lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, Looper.getMainLooper());
        } catch (RuntimeException e) { // SecurityException or a missing provider
            ipFallback(ctx, cb, main, stale);
            return;
        }
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (done[0]) return;
                done[0] = true;
                try { lm.removeUpdates(listener); } catch (SecurityException ignored) { }
                ipFallback(ctx, cb, main, stale);
            }
        }, 25000);
    }

    /** Use an older fix if we have one, otherwise guess from the IP address. */
    private static void ipFallback(final Context ctx, final Callback cb, final Handler main, final Location stale) {
        if (stale != null) {
            name(ctx, stale.getLatitude(), stale.getLongitude(), true, cb, main);
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Map<String, Object> m = United.map(Json.parse(Net.get("http://ip-api.com/json/?fields=status,city,lat,lon")));
                    if (!"success".equals(m.get("status")) || !(m.get("lat") instanceof Double)) throw new IllegalStateException();
                    final double lat = (Double) m.get("lat"), lon = (Double) m.get("lon");
                    final String city = United.str(m.get("city"));
                    main.post(new Runnable() { @Override public void run() { cb.found(lat, lon, city.isEmpty() ? "My location" : city, false); } });
                } catch (final Exception e) {
                    main.post(new Runnable() { @Override public void run() { cb.failed(Net.describe(e)); } });
                }
            }
        }, "locate").start();
    }

    /** Gives the place a name: the tablet's geocoder if it has one, else the nearest town from the IP lookup. */
    private static void name(final Context ctx, final double lat, final double lon, final boolean precise, final Callback cb, final Handler main) {
        new Thread(new Runnable() {
            @Override public void run() {
                String name = null;
                try {
                    if (Geocoder.isPresent()) {
                        List<Address> found = new Geocoder(ctx, Locale.UK).getFromLocation(lat, lon, 1);
                        if (found != null && !found.isEmpty()) {
                            Address a = found.get(0);
                            name = a.getLocality() != null ? a.getLocality() : a.getSubAdminArea();
                        }
                    }
                } catch (Exception ignored) { }
                if (name == null) {
                    try {
                        Map<String, Object> m = United.map(Json.parse(Net.get("http://ip-api.com/json/?fields=status,city,lat,lon")));
                        if (m.get("lat") instanceof Double && distanceKm(lat, lon, (Double) m.get("lat"), (Double) m.get("lon")) < 40) {
                            name = United.str(m.get("city"));
                        }
                    } catch (Exception ignored) { }
                }
                final String label = name == null || name.isEmpty() ? "My location" : name;
                main.post(new Runnable() { @Override public void run() { cb.found(lat, lon, label, precise); } });
            }
        }, "place-name").start();
    }

    static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371, dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }
}
