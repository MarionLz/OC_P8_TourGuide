package com.openclassrooms.tourguide.service;

import com.openclassrooms.tourguide.DTO.NearByAttractionDTO;
import com.openclassrooms.tourguide.helper.InternalTestHelper;
import com.openclassrooms.tourguide.tracker.Tracker;
import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import gpsUtil.GpsUtil;
import gpsUtil.location.Attraction;
import gpsUtil.location.Location;
import gpsUtil.location.VisitedLocation;

import org.w3c.dom.Attr;
import tripPricer.Provider;
import tripPricer.TripPricer;

@Service
public class TourGuideService {
	private Logger logger = LoggerFactory.getLogger(TourGuideService.class);
	private final GpsUtil gpsUtil;
	private final RewardsService rewardsService;
	private final TripPricer tripPricer = new TripPricer();
	public final Tracker tracker;
	boolean testMode = true;
	private final ExecutorService executor;

	public TourGuideService(GpsUtil gpsUtil, RewardsService rewardsService) {
		this.gpsUtil = gpsUtil;
		this.rewardsService = rewardsService;
		this.executor = Executors.newFixedThreadPool(100);

		Locale.setDefault(Locale.US);

		if (testMode) {
			logger.info("TestMode enabled");
			logger.debug("Initializing users");
			initializeInternalUsers();
			logger.debug("Finished initializing users");
		}
		tracker = new Tracker(this);
		addShutDownHook();
	}

	public List<UserReward> getUserRewards(User user) {
		return user.getUserRewards();
	}

	public VisitedLocation getUserLocation(User user) {
		VisitedLocation visitedLocation = (user.getVisitedLocations().size() > 0) ? user.getLastVisitedLocation()
				: trackUserLocation(user);
		return visitedLocation;
	}

	public User getUser(String userName) {
		return internalUserMap.get(userName);
	}

	public List<User> getAllUsers() {
		return internalUserMap.values().stream().collect(Collectors.toList());
	}

	public void addUser(User user) {
		if (!internalUserMap.containsKey(user.getUserName())) {
			internalUserMap.put(user.getUserName(), user);
		}
	}

	public List<Provider> getTripDeals(User user) {
		int cumulativeRewardPoints = user.getUserRewards().stream().mapToInt(i -> i.getRewardPoints()).sum();

		List<Provider> allProviders = new ArrayList<>();

		while (allProviders.size() < 10) {
			List<Provider> batch = tripPricer.getPrice(
					tripPricerApiKey,
					user.getUserId(),
					user.getUserPreferences().getNumberOfAdults(),
					user.getUserPreferences().getNumberOfChildren(),
					user.getUserPreferences().getTripDuration(),
					cumulativeRewardPoints
			);
			int remaining = 10 - allProviders.size();
			allProviders.addAll(
					batch.stream()
							.limit(remaining)
							.collect(Collectors.toList())
			);
			if (batch.isEmpty()) {
				break;
			}
		}

		user.setTripDeals(allProviders);
		return allProviders;
	}

	public VisitedLocation trackUserLocation(User user) {
		VisitedLocation visitedLocation = gpsUtil.getUserLocation(user.getUserId());
		user.addToVisitedLocations(visitedLocation);
		rewardsService.calculateRewards(user);
		return visitedLocation;
	}

	public CompletableFuture<VisitedLocation> trackUserLocationAsync(User user) {
		return CompletableFuture.supplyAsync(() -> trackUserLocation(user), executor); // 👈 ici

	}

	public void trackAllUsersAsync(List<User> users) {
		List<CompletableFuture<Void>> futures = users.stream()
				.map(user -> trackUserLocationAsync(user).thenAccept(loc -> {})) // discard result
				.collect(Collectors.toList());

		CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
	}

	public void shutdown() {
		executor.shutdown();
	}

	public List<NearByAttractionDTO> getNearByAttractions(VisitedLocation visitedLocation, User user) {
		List<NearByAttractionDTO> nearbyAttractions = new ArrayList<>();
		List<Attraction> attractions = gpsUtil.getAttractions();
		Map<Attraction, Double> attractionUserDistances = new HashMap<>();

		for (Attraction attraction : attractions) {
			attractionUserDistances.put(attraction, rewardsService.getDistance(attraction, visitedLocation.location));
		}

		List<Map.Entry<Attraction, Double>> sortedList = new ArrayList<>(attractionUserDistances.entrySet());
		sortedList.sort(Comparator.comparingDouble(Map.Entry::getValue));

		List<Map.Entry<Attraction, Double>> top5Closest = sortedList.subList(0, Math.min(5, sortedList.size()));

		for (Map.Entry<Attraction, Double> entry : top5Closest) {
			Attraction attraction = entry.getKey();
			int rewardPoints = rewardsService.getRewardPoints(attraction, user);

			NearByAttractionDTO nearByAttractionDTO = new NearByAttractionDTO();
			nearByAttractionDTO.setAttractionName(attraction.attractionName);
			nearByAttractionDTO.setAttractionLatitude(attraction.latitude);
			nearByAttractionDTO.setAttractionLongitude(attraction.longitude);
			nearByAttractionDTO.setUserLatitude(visitedLocation.location.latitude);
			nearByAttractionDTO.setUserLongitude(visitedLocation.location.longitude);
			nearByAttractionDTO.setDistanceInMiles(entry.getValue());
			nearByAttractionDTO.setRewardPoints(rewardPoints);

			nearbyAttractions.add(nearByAttractionDTO);
		}

		return nearbyAttractions;
	}

	private void addShutDownHook() {
		Runtime.getRuntime().addShutdownHook(new Thread() {
			public void run() {
				tracker.stopTracking();
			}
		});
	}

	/**********************************************************************************
	 * 
	 * Methods Below: For Internal Testing
	 * 
	 **********************************************************************************/
	private static final String tripPricerApiKey = "test-server-api-key";
	// Database connection will be used for external users, but for testing purposes
	// internal users are provided and stored in memory
	private final Map<String, User> internalUserMap = new HashMap<>();

	private void initializeInternalUsers() {
		IntStream.range(0, InternalTestHelper.getInternalUserNumber()).forEach(i -> {
			String userName = "internalUser" + i;
			String phone = "000";
			String email = userName + "@tourGuide.com";
			User user = new User(UUID.randomUUID(), userName, phone, email);
			generateUserLocationHistory(user);

			internalUserMap.put(userName, user);
		});
		logger.debug("Created " + InternalTestHelper.getInternalUserNumber() + " internal test users.");
	}

	private void generateUserLocationHistory(User user) {
		IntStream.range(0, 3).forEach(i -> {
			user.addToVisitedLocations(new VisitedLocation(user.getUserId(),
					new Location(generateRandomLatitude(), generateRandomLongitude()), getRandomTime()));
		});
	}

	private double generateRandomLongitude() {
		double leftLimit = -180;
		double rightLimit = 180;
		return leftLimit + new Random().nextDouble() * (rightLimit - leftLimit);
	}

	private double generateRandomLatitude() {
		double leftLimit = -85.05112878;
		double rightLimit = 85.05112878;
		return leftLimit + new Random().nextDouble() * (rightLimit - leftLimit);
	}

	private Date getRandomTime() {
		LocalDateTime localDateTime = LocalDateTime.now().minusDays(new Random().nextInt(30));
		return Date.from(localDateTime.toInstant(ZoneOffset.UTC));
	}

}
