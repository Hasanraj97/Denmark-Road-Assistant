package com.example.backend.dto;

/**
 * One real, currently reported traffic event that lies on the user's route.
 *
 * <p>Unlike almost everything else the model touches, this is <strong>not</strong>
 * derived or estimated. It comes straight from Vejdirektoratet, the Danish Road
 * Directorate, and describes something they say is actually happening on a
 * specific stretch of road.</p>
 *
 * <p>It is shown to the user but deliberately <em>not</em> fed into the model. The
 * road-condition grade is produced by a model trained on generated data, and its
 * {@code construction_risk_score} feature is an assumption about how likely
 * roadworks are, not a count of real ones. Mixing a real count into a model that
 * never saw one during training would recreate exactly the train/serve mismatch
 * this project spent its largest phase removing.</p>
 *
 * @param category   plain-English grouping, e.g. "Roadworks", "Accident", "Queue"
 * @param title      Vejdirektoratet's own short title, in Danish
 * @param headline   their one-line summary naming the road and the stretch
 * @param latitude   where it is
 * @param longitude  where it is
 * @param distanceFromRouteMetres how far off the driven line it sits
 * @param startsAt   when it began or is due to begin, as displayed by Vejdirektoratet
 * @param endsAt     when it is expected to clear, or null if open-ended
 * @param activeAtDeparture whether it is expected to still be going at the
 *                   departure time the user chose
 */
public record RouteIncidentDto(
        String category,
        String title,
        String headline,
        double latitude,
        double longitude,
        int distanceFromRouteMetres,
        String startsAt,
        String endsAt,
        boolean activeAtDeparture
) {
}
