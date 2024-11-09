package com.openclassrooms.tourguide.service;

import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;
import gpsUtil.GpsUtil;
import gpsUtil.location.Attraction;
import gpsUtil.location.Location;
import gpsUtil.location.VisitedLocation;
import org.springframework.stereotype.Service;
import rewardCentral.RewardCentral;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
public class RewardsService {
    private static final double STATUTE_MILES_PER_NAUTICAL_MILE = 1.15077945;

    // proximity in miles
    private int defaultProximityBuffer = 10;
    private int proximityBuffer = defaultProximityBuffer;
    private int attractionProximityRange = 200;

    private final GpsUtil gpsUtil;
    private final RewardCentral rewardsCentral;
    private final List<Attraction> attractions;

    // Cache pour les points de récompense
    private final Map<String, Integer> rewardPointsCache = new ConcurrentHashMap<>();

    public RewardsService(GpsUtil gpsUtil, RewardCentral rewardCentral) {
        this.gpsUtil = gpsUtil;
        this.rewardsCentral = rewardCentral;
        this.attractions = gpsUtil.getAttractions(); // Cache attractions at instantiation
    }

    // ExecutorService avec un pool de threads fixe pour contrôler le nombre de threads en parallèle
    private final ExecutorService executorService = Executors.newFixedThreadPool(100);

    public void setProximityBuffer(int proximityBuffer) {
        this.proximityBuffer = proximityBuffer;
    }

    public void setDefaultProximityBuffer() {
        proximityBuffer = defaultProximityBuffer;
    }

    public CompletableFuture<Void> calculateRewards(User user) {
        List<VisitedLocation> userLocations = user.getVisitedLocations().stream().toList();
        Set<String> rewardedAttractions = user.getUserRewards().stream()
                .map(r -> r.attraction.attractionName)
                .collect(Collectors.toSet());

        List<CompletableFuture<Void>> futures = userLocations.stream()
                .flatMap(visitedLocation -> attractions.stream()
                        .filter(attraction -> !rewardedAttractions.contains(attraction.attractionName))
                        .map(attraction -> CompletableFuture.runAsync(() -> {
                            if (nearAttraction(visitedLocation, attraction)) {
                                int rewardPoints = getRewardPoints(attraction, user);
                                user.addUserReward(new UserReward(visitedLocation, attraction, rewardPoints));
                            }
                        }, executorService))
                ).toList();

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    public boolean isWithinAttractionProximity(Attraction attraction, Location location) {
        return getDistance(attraction, location) > attractionProximityRange ? false : true;
    }

    private boolean nearAttraction(VisitedLocation visitedLocation, Attraction attraction) {
        return getDistance(attraction, visitedLocation.location) > proximityBuffer ? false : true;
    }

    private int getRewardPoints(Attraction attraction, User user) {
        String key = attraction.attractionId + "-" + user.getUserId();
        return rewardPointsCache.computeIfAbsent(key, k -> rewardsCentral.getAttractionRewardPoints(attraction.attractionId, user.getUserId()));
    }

    public double getDistance(Location loc1, Location loc2) {
        double lat1 = Math.toRadians(loc1.latitude);
        double lon1 = Math.toRadians(loc1.longitude);
        double lat2 = Math.toRadians(loc2.latitude);
        double lon2 = Math.toRadians(loc2.longitude);

        double angle = Math.acos(Math.sin(lat1) * Math.sin(lat2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.cos(lon1 - lon2));

        double nauticalMiles = 60 * Math.toDegrees(angle);
        double statuteMiles = STATUTE_MILES_PER_NAUTICAL_MILE * nauticalMiles;
        return statuteMiles;
    }

}