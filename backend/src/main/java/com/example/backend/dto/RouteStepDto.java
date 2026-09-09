package com.example.backend.dto;

/**
 * One turn-by-turn instruction along the route.
 *
 * <p>Produced from OSRM's {@code steps} output. The route request previously
 * asked only for the overall geometry, which was enough to draw a line on a map
 * but not to guide anyone along it.</p>
 *
 * @param instruction    a plain-English sentence, e.g. "Turn left onto Vestergade"
 * @param maneuverType   OSRM's maneuver type: depart, turn, merge, roundabout,
 *                       arrive and so on. Used to pick an icon.
 * @param modifier       direction qualifier: left, right, slight left, straight...
 * @param roadName       the road being joined, blank for unnamed roads
 * @param distanceMetres how far this step runs before the next instruction
 * @param durationSeconds how long OSRM expects this step to take
 * @param latitude       where the manoeuvre happens
 * @param longitude      where the manoeuvre happens
 * @param geometryIndex  index into the route geometry where this step begins, so
 *                       the app can tell how far along the driver already is
 *                       without recomputing distances from scratch
 */
public record RouteStepDto(
        String instruction,
        String maneuverType,
        String modifier,
        String roadName,
        double distanceMetres,
        double durationSeconds,
        double latitude,
        double longitude,
        int geometryIndex
) {
}
