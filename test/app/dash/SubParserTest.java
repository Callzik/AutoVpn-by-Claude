package app.dash;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Plain-JVM test of SubParser (no Android, no JUnit): runs testdata/sub-cases.json, which the Windows
 * parser test (windows/parse_test.go) checks too, so both clients parse subscriptions the same way.
 * Usage: java app.dash.SubParserTest testdata/sub-cases.json
 */
public final class SubParserTest {
    private static int failures;

    private static void fail(String caseName, String msg) {
        failures++;
        System.out.println("FAIL " + caseName + ": " + msg);
    }

    /** Through text and back, so Integer/Long and key order don't matter in equals(). */
    private static Object norm(Object o) {
        return o == null ? null : Json.parse(Json.write(o));
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        String data = new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8);
        List<Object> cases = (List<Object>) Json.parse(data);
        for (Object co : cases) {
            Map<String, Object> c = (Map<String, Object>) co;
            String name = (String) c.get("name");
            List<String> warnings = new ArrayList<>();
            List<Server> got;
            try {
                got = SubParser.parse((String) c.get("sub"), warnings);
            } catch (Exception e) {
                fail(name, "exception " + e);
                continue;
            }
            long wantWarnings = (Long) c.get("warnings");
            if (warnings.size() != wantWarnings) fail(name, "warnings: got " + warnings.size() + " " + warnings + ", want " + wantWarnings);
            List<Object> want = (List<Object>) c.get("servers");
            if (got.size() != want.size()) {
                fail(name, "servers: got " + got.size() + ", want " + want.size());
                continue;
            }
            for (int i = 0; i < want.size(); i++) {
                Map<String, Object> w = (Map<String, Object>) want.get(i);
                Server s = got.get(i);
                if (!s.name.equals(w.get("name")) || s.group != (Long) w.get("group") || !s.host.equals(w.get("host"))) {
                    fail(name, "server " + i + ": got " + s.name + "/" + s.group + "/" + s.host
                            + ", want " + w.get("name") + "/" + w.get("group") + "/" + w.get("host"));
                }
                Object ob = norm(s.outbound);
                if (!ob.equals(w.get("outbound"))) {
                    fail(name, "server " + i + " outbound:\n got " + Json.write(ob) + "\nwant " + Json.write(w.get("outbound")));
                }
                Object xr = norm(s.xray);
                if (xr == null ? w.get("xray") != null : !xr.equals(w.get("xray"))) {
                    fail(name, "server " + i + " xray:\n got " + Json.write(xr) + "\nwant " + Json.write(w.get("xray")));
                }
            }
        }
        if (failures > 0) {
            System.out.println(failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("ok: " + cases.size() + " cases");
    }
}
