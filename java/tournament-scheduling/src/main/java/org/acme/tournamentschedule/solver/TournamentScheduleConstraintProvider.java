package org.acme.tournamentschedule.solver;

import static ai.timefold.solver.core.api.score.stream.Joiners.equal;
import static ai.timefold.solver.core.api.score.stream.Joiners.lessThan;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import ai.timefold.solver.core.api.function.TriFunction;
import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.core.api.score.stream.bi.BiConstraintCollector;
import ai.timefold.solver.core.api.score.stream.uni.UniConstraintCollector;

import org.acme.tournamentschedule.domain.TeamAssignment;
import org.acme.tournamentschedule.domain.UnavailabilityPenalty;

public class TournamentScheduleConstraintProvider implements ConstraintProvider {

    private static <A> UniConstraintCollector<A, ?, LoadBalanceData> loadBalance(Function<A, Object> groupKey) {
        return new LoadBalanceUniConstraintCollector<>(groupKey);
    }

    private static <A, B> BiConstraintCollector<A, B, ?, LoadBalanceData> loadBalance(BiFunction<A, B, Object> groupKey) {
        return new LoadBalanceBiConstraintCollector<>(groupKey);
    }

    @Override
    public Constraint[] defineConstraints(ConstraintFactory constraintFactory) {
        return new Constraint[] {
                oneAssignmentPerDatePerTeam(constraintFactory),
                unavailabilityPenalty(constraintFactory),
                fairAssignmentCountPerTeam(constraintFactory),
                evenlyConfrontationCount(constraintFactory)
        };
    }

    Constraint oneAssignmentPerDatePerTeam(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(TeamAssignment.class)
                .join(TeamAssignment.class,
                        equal(TeamAssignment::getTeam),
                        equal(TeamAssignment::getDay),
                        lessThan(TeamAssignment::getId))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("oneAssignmentPerDatePerTeam");
    }

    Constraint unavailabilityPenalty(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(UnavailabilityPenalty.class)
                .ifExists(TeamAssignment.class,
                        equal(UnavailabilityPenalty::getTeam, TeamAssignment::getTeam),
                        equal(UnavailabilityPenalty::getDay, TeamAssignment::getDay))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("unavailabilityPenalty");
    }

    Constraint fairAssignmentCountPerTeam(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(TeamAssignment.class)
                .groupBy(loadBalance(TeamAssignment::getTeam))
                .penalize(HardMediumSoftScore.ONE_MEDIUM,
                        result -> (int) result.getZeroDeviationSquaredSumRootMillis())
                .asConstraint("fairAssignmentCountPerTeam");
    }

    Constraint evenlyConfrontationCount(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(TeamAssignment.class)
                .join(TeamAssignment.class,
                        equal(TeamAssignment::getDay),
                        lessThan(assignment -> assignment.getTeam().getId()))
                .groupBy(loadBalance(
                        (assignment, otherAssignment) -> new Pair<>(assignment.getTeam(), otherAssignment.getTeam())))
                .penalize(HardMediumSoftScore.ONE_SOFT,
                        result -> (int) result.getZeroDeviationSquaredSumRootMillis())
                .asConstraint("evenlyConfrontationCount");
    }

    public static final class LoadBalanceData {

        private final Map<Object, Long> groupCountMap = new LinkedHashMap<>(0);
        // the sum of squared deviation from zero
        private long squaredSum = 0L;

        private Runnable apply(Object mapped) {
            long count = groupCountMap.compute(mapped,
                    (key, value) -> (value == null) ? 1L : value + 1L);
            // squaredZeroDeviation = squaredZeroDeviation - (count - 1)² + count²
            // <=> squaredZeroDeviation = squaredZeroDeviation + (2 * count - 1)
            squaredSum += (2 * count - 1);
            return () -> {
                Long computed = groupCountMap.compute(mapped,
                        (key, value) -> (value == 1L) ? null : value - 1L);
                squaredSum -= (computed == null) ? 1L : (2 * computed + 1);
            };
        }

        public long getZeroDeviationSquaredSumRootMillis() {
            return (long) (Math.sqrt(squaredSum) * 1_000);
        }

    }

    public record Pair<A, B>(A key, B value) {

    }

    public static class LoadBalanceUniConstraintCollector<A>
            implements UniConstraintCollector<A, LoadBalanceData, LoadBalanceData> {

        private final Function<A, Object> groupKey;

        public LoadBalanceUniConstraintCollector(Function<A, Object> groupKey) {
            this.groupKey = groupKey;
        }

        @Override
        public Supplier<LoadBalanceData> supplier() {
            return LoadBalanceData::new;
        }

        @Override
        public BiFunction<LoadBalanceData, A, Runnable> accumulator() {
            return (resultContainer, a) -> {
                Object mapped = groupKey.apply(a);
                return resultContainer.apply(mapped);
            };
        }

        @Override
        public Function<LoadBalanceData, LoadBalanceData> finisher() {
            return Function.identity();
        }
    }

    public static class LoadBalanceBiConstraintCollector<A, B>
            implements BiConstraintCollector<A, B, LoadBalanceData, LoadBalanceData> {

        private final BiFunction<A, B, Object> groupKey;

        public LoadBalanceBiConstraintCollector(BiFunction<A, B, Object> groupKey) {
            this.groupKey = groupKey;
        }

        @Override
        public Supplier<LoadBalanceData> supplier() {
            return LoadBalanceData::new;
        }

        @Override
        public TriFunction<LoadBalanceData, A, B, Runnable> accumulator() {
            return (resultContainer, a, b) -> {
                Object mapped = groupKey.apply(a, b);
                return resultContainer.apply(mapped);
            };
        }

        @Override
        public Function<LoadBalanceData, LoadBalanceData> finisher() {
            return Function.identity();
        }
    }
}
