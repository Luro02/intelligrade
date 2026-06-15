/* Licensed under EPL-2.0 2024-2026. */
package edu.kit.kastel.sdq.intelligrade.extensions.guis.table;

import java.util.Comparator;
import java.util.List;

import edu.kit.kastel.sdq.artemis4j.grading.Annotation;
import edu.kit.kastel.sdq.artemis4j.grading.location.Location;
import edu.kit.kastel.sdq.artemis4j.grading.location.LocationFormatter;
import org.jspecify.annotations.NonNull;

public record Locations(List<Location> locations) implements Comparable<Locations> {
    public static Locations fromLocations(List<Locations> locations) {
        return new Locations(
                locations.stream().flatMap(l -> l.locations.stream()).toList());
    }

    public static Locations fromAnnotation(Annotation annotation) {
        return new Locations(List.of(annotation.getLocation()));
    }

    @Override
    public @NonNull String toString() {
        LocationFormatter formatter =
                new LocationFormatter().removeSharedPrefix(true).enableLineMerging();

        for (Location location : locations) {
            formatter.addLocation(location);
        }

        return formatter.format();
    }

    @Override
    public int compareTo(@NonNull Locations other) {
        var left = locations.stream().sorted(BY_SOURCE_POSITION).toList();
        var right = other.locations.stream().sorted(BY_SOURCE_POSITION).toList();

        for (int i = 0; i < Math.min(left.size(), right.size()); i++) {
            var comparison = BY_SOURCE_POSITION.compare(left.get(i), right.get(i));
            if (comparison != 0) {
                return comparison;
            }
        }

        return Integer.compare(left.size(), right.size());
    }

    private static final Comparator<Location> BY_SOURCE_POSITION =
            Comparator.comparing(Location::start).thenComparing(Location::end);
}
