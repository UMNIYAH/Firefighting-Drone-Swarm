package swarm.infra;

import swarm.model.Position;
import swarm.model.Zone;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads and manages zone definitions from CSV.
 *
 * Expected format:
 * Zone ID, Zone Start, Zone End
 * n, (x1; y1), (x2; y2)
 */
public class ZoneManager {

    private final Map<Integer, Zone> zones = new HashMap<>();

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
                String[] parts = line.split(",");
                if (parts.length == 3) {
                    int id = Integer.parseInt(parts[0].trim());
                    Position start = parsePosition(parts[1].trim());
                    Position end = parsePosition(parts[2].trim());
                    zones.put(id, new Zone(id, start, end));
                } else {
                    System.err.println("[ZoneManager] Skipping line: " + line);
                }
            }
        }
    }

    private Position parsePosition(String token) {
        // token like "(700,600)" or "(700, 600)"
        String cleaned = token.replace("(", "").replace(")", "");
        String[] xy = cleaned.split("\\s*;\\s*");
        double x = Double.parseDouble(xy[0]);
        double y = Double.parseDouble(xy[1]);
        return new Position(x, y);
    }

    public Zone getZone(int id) {
        return zones.get(id);
    }

    public Position getZoneCenter(int id) {
        Zone z = zones.get(id);
        return (z == null) ? null : z.center();
    }
}
