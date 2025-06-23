package com.openclassrooms.tourguide.DTO;

import lombok.Data;

@Data
public class NearByAttractionDTO {

    String attractionName;
    double attractionLatitude;
    double attractionLongitude;
    double userLatitude;
    double userLongitude;
    double distanceInMiles;
    int rewardPoints;
}
