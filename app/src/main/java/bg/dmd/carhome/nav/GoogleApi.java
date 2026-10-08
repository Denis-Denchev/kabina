package bg.dmd.carhome.nav;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Google Places (New) text search and Routes API v2, in Bulgarian. */
public final class GoogleApi {
    private final String key;

    public GoogleApi(String key) { this.key = key; }

    /** The phone's language and country, so search results, street names and spoken turns match the user. */
    private static String lang() { return java.util.Locale.getDefault().getLanguage(); }
    private static String region() { String c = java.util.Locale.getDefault().getCountry(); return c.length() == 2 ? c : null; }
    private static String langTag() {
        java.util.Locale l = java.util.Locale.getDefault();
        return region() == null ? l.getLanguage() : l.getLanguage() + "-" + l.getCountry();
    }

    public static class Place {
        public String name, address;
        public double lat, lng;
    }

    public static class Step {
        public String maneuver, text;
        public double meters;
        public double endAlong;          // distance along the route where this step ends
        public List<double[]> points;
    }

    public static class Route {
        public List<double[]> points = new ArrayList<double[]>();
        public List<Step> steps = new ArrayList<Step>();
        public double meters, seconds;
    }

    /** Night style for Google 2D tiles. */
    private static final String STYLE = "["
            + "{\"elementType\":\"geometry\",\"stylers\":[{\"color\":\"#101318\"}]},"
            + "{\"elementType\":\"labels.text.fill\",\"stylers\":[{\"color\":\"#8a929e\"}]},"
            + "{\"elementType\":\"labels.text.stroke\",\"stylers\":[{\"color\":\"#0b0d10\"}]},"
            + "{\"elementType\":\"labels.icon\",\"stylers\":[{\"visibility\":\"off\"}]},"
            + "{\"featureType\":\"administrative\",\"elementType\":\"geometry\",\"stylers\":[{\"color\":\"#2a2f37\"}]},"
            + "{\"featureType\":\"poi\",\"stylers\":[{\"visibility\":\"off\"}]},"
            + "{\"featureType\":\"poi.park\",\"elementType\":\"geometry\",\"stylers\":[{\"visibility\":\"on\"},{\"color\":\"#111a15\"}]},"
            + "{\"featureType\":\"road\",\"elementType\":\"geometry\",\"stylers\":[{\"color\":\"#262b33\"}]},"
            + "{\"featureType\":\"road\",\"elementType\":\"geometry.stroke\",\"stylers\":[{\"color\":\"#14171c\"}]},"
            + "{\"featureType\":\"road.arterial\",\"elementType\":\"geometry\",\"stylers\":[{\"color\":\"#2f353f\"}]},"
            + "{\"featureType\":\"road.highway\",\"elementType\":\"geometry\",\"stylers\":[{\"color\":\"#3a414c\"}]},"
            + "{\"featureType\":\"road.highway\",\"elementType\":\"labels.text.fill\",\"stylers\":[{\"color\":\"#b4bcc8\"}]},"
            + "{\"featureType\":\"transit\",\"stylers\":[{\"visibility\":\"off\"}]},"
            + "{\"featureType\":\"water\",\"elementType\":\"geometry\",\"stylers\":[{\"color\":\"#0a1520\"}]},"
            + "{\"featureType\":\"water\",\"elementType\":\"labels.text.fill\",\"stylers\":[{\"color\":\"#3d5a75\"}]}"
            + "]";

    /** Day style: light, low-noise, white roads, readable in sunlight. */
    private static final String DAY_STYLE = "["
            + "{\"elementType\":\"geometry\",\"stylers\":[{\"color\":\"#eef0f3\"}]},"
            + "{\"elementType\":\"labels.text.fill\",\"stylers\":[{\"color\":\"#3b4350\"}]},"
            + "{\"elementType\":\"labels.text.stroke\",\"stylers\":[{\"color\":\"#ffffff\"}]},"
            + "{\"elementType\":\"labels.icon\",\"stylers\":[{\"visibility\":\"off\"}]},"
            + "{\"featureType\":\"administrative\",\"elementType\":\"geometry\",\"stylers\":[{\"color\":\"#c3c9d2\"}]},"
            + "{\"featureType\":\"poi\",\"stylers\":[{\"visibility\":\"off\"}]},"
            + "{\"featureType\":\"poi.park\",\"elementType\":\"geometry\",\"stylers\":[{\"visibility\":\"on\"},{\"color\":\"#dcebdc\"}]},"
            + "{\"featureType\":\"road\",\"elementType\":\"geometry\",\"stylers\":[{\"color\":\"#ffffff\"}]},"
            + "{\"featureType\":\"road\",\"elementType\":\"geometry.stroke\",\"stylers\":[{\"color\":\"#cfd4db\"}]},"
            + "{\"featureType\":\"road.highway\",\"elementType\":\"geometry\",\"stylers\":[{\"color\":\"#ffe1a3\"}]},"
            + "{\"featureType\":\"road.highway\",\"elementType\":\"geometry.stroke\",\"stylers\":[{\"color\":\"#e3b866\"}]},"
            + "{\"featureType\":\"road.highway\",\"elementType\":\"labels.text.fill\",\"stylers\":[{\"color\":\"#1f2937\"}]},"
            + "{\"featureType\":\"transit\",\"stylers\":[{\"visibility\":\"off\"}]},"
            + "{\"featureType\":\"water\",\"elementType\":\"geometry\",\"stylers\":[{\"color\":\"#bcd6ee\"}]},"
            + "{\"featureType\":\"water\",\"elementType\":\"labels.text.fill\",\"stylers\":[{\"color\":\"#4d6f91\"}]}"
            + "]";

    private static String style() { return bg.dmd.carhome.Theme.day ? DAY_STYLE : STYLE; }

    /** Creates a Map Tiles API session. Returns {sessionToken, expiryEpochSeconds}. */
    public String[] tileSession() throws Exception {
        JSONObject body = new JSONObject()
                .put("mapType", "roadmap")
                .put("language", langTag())
                .put("region", region() == null ? "US" : region())
                .put("scale", "scaleFactor2x")
                .put("highDpi", true)
                .put("styles", new JSONArray(style()));
        String res = Net.postJson("https://tile.googleapis.com/v1/createSession?key=" + key, body.toString(), new HashMap<String, String>());
        JSONObject o = new JSONObject(res);
        return new String[]{o.getString("session"), o.optString("expiry", "0")};
    }

    public MapView.TileSource tiles(final String session) {
        final String style = style();
        return new MapView.TileSource() {
            public String url(int z, int x, int y) {
                return "https://tile.googleapis.com/v1/2dtiles/" + z + "/" + x + "/" + y + "?session=" + session + "&key=" + key;
            }
            public String id() { return "g" + Integer.toHexString(style.hashCode()); }
            public boolean darken() { return false; }
        };
    }

    private Map<String, String> headers(String mask) {
        Map<String, String> h = new HashMap<String, String>();
        h.put("X-Goog-Api-Key", key);
        h.put("X-Goog-FieldMask", mask);
        return h;
    }

    public List<Place> search(String query, double lat, double lng) throws Exception {
        JSONObject body = new JSONObject();
        body.put("textQuery", query);
        body.put("languageCode", lang());
        if (region() != null) body.put("regionCode", region());
        body.put("maxResultCount", 8);
        if (!Double.isNaN(lat)) {
            JSONObject circle = new JSONObject();
            circle.put("center", new JSONObject().put("latitude", lat).put("longitude", lng));
            circle.put("radius", 50000.0);
            body.put("locationBias", new JSONObject().put("circle", circle));
        }
        String res = Net.postJson("https://places.googleapis.com/v1/places:searchText", body.toString(),
                headers("places.displayName,places.formattedAddress,places.location"));
        List<Place> out = new ArrayList<Place>();
        JSONArray arr = new JSONObject(res).optJSONArray("places");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.getJSONObject(i);
            Place pl = new Place();
            pl.name = p.optJSONObject("displayName") != null ? p.getJSONObject("displayName").optString("text") : "";
            pl.address = p.optString("formattedAddress");
            JSONObject loc = p.getJSONObject("location");
            pl.lat = loc.getDouble("latitude");
            pl.lng = loc.getDouble("longitude");
            out.add(pl);
        }
        return out;
    }

    public Route route(double fromLat, double fromLng, double heading, double toLat, double toLng) throws Exception {
        JSONObject origin = new JSONObject().put("location", new JSONObject()
                .put("latLng", new JSONObject().put("latitude", fromLat).put("longitude", fromLng))
                .put("heading", (int) Math.round(heading) % 360));
        JSONObject dest = new JSONObject().put("location", new JSONObject()
                .put("latLng", new JSONObject().put("latitude", toLat).put("longitude", toLng)));
        JSONObject body = new JSONObject()
                .put("origin", origin)
                .put("destination", dest)
                .put("travelMode", "DRIVE")
                .put("routingPreference", "TRAFFIC_AWARE")
                .put("languageCode", lang())
                .put("units", "METRIC");
        String res = Net.postJson("https://routes.googleapis.com/directions/v2:computeRoutes", body.toString(),
                headers("routes.duration,routes.distanceMeters,routes.polyline.encodedPolyline,"
                        + "routes.legs.steps.distanceMeters,routes.legs.steps.polyline.encodedPolyline,"
                        + "routes.legs.steps.navigationInstruction"));
        JSONArray routes = new JSONObject(res).optJSONArray("routes");
        if (routes == null || routes.length() == 0) throw new Exception(bg.dmd.carhome.App.s(bg.dmd.carhome.R.string.nav_no_route));
        JSONObject r = routes.getJSONObject(0);
        Route out = new Route();
        out.meters = r.optDouble("distanceMeters", 0);
        out.seconds = Double.parseDouble(r.optString("duration", "0s").replace("s", ""));
        out.points = Geo.decode(r.getJSONObject("polyline").getString("encodedPolyline"));
        double along = 0;
        JSONArray legs = r.getJSONArray("legs");
        for (int l = 0; l < legs.length(); l++) {
            JSONArray steps = legs.getJSONObject(l).optJSONArray("steps");
            if (steps == null) continue;
            for (int i = 0; i < steps.length(); i++) {
                JSONObject s = steps.getJSONObject(i);
                Step st = new Step();
                st.meters = s.optDouble("distanceMeters", 0);
                JSONObject ni = s.optJSONObject("navigationInstruction");
                st.maneuver = ni == null ? "STRAIGHT" : ni.optString("maneuver", "STRAIGHT");
                st.text = ni == null ? "" : ni.optString("instructions", "");
                st.points = Geo.decode(s.getJSONObject("polyline").getString("encodedPolyline"));
                along += st.meters;
                st.endAlong = along;
                out.steps.add(st);
            }
        }
        return out;
    }
}
