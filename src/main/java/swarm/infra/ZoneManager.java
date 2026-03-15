package swarm.infra;

import swarm.model.Position;
import swarm.model.Zone;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ZoneManager {

    private final Map<Integer, Zone> zones = new HashMap<>();

    // Matches: ID, (x1, y1), (x2, y2)  with flexible spacing
    private static final Pattern ZONE_PATTERN =
            Pattern.compile("\\s*(\\d+)\\s*,\\s*\\(\\s*([\\d.]+)\\s*,\\s*([\\d.]+)\\s*\\)\\s*,\\s*\\(\\s*([\\d.]+)\\s*,\\s*([\\d.]+)\\s*\\)");

    public ZoneManager(String zoneFilePath) throws IOException {
        loadZones(zoneFilePath);
    }

    private void loadZones(String path) throws IOException {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty() || line.trim().toLowerCase().startsWith("zone")) {
                    continue;
                }
                Matcher m = ZONE_PATTERN.matcher(line);
                if (m.matches()) {
                    int id = Integer.parseInt(m.group(1));
                    Position start = new Position(Double.parseDouble(m.group(2)), Double.parseDouble(m.group(3)));
                    Position end = new Position(Double.parseDouble(m.group(4)), Double.parseDouble(m.group(5)));
                    zones.put(id, new Zone(id, start, end));
                    System.out.println("[ZoneManager] Loaded Zone " + id);
                } else {
                    System.err.println("[ZoneManager] Skipping line: " + line);
                }
            }
        }
    }

    public Zone getZone(int id) {
        return zones.get(id);
    }

    public Position getZoneCenter(int id) {
        Zone z = zones.get(id);
        return (z == null) ? null : z.center();
    }
}