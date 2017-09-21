/**
 *
 The MIT License (MIT)

 Copyright (c) 2015, Kyriakos Georgiou, Data Management Systems Laboratory (DMSL)
 Department of Computer Science, University of Cyprus, Nicosia, CYPRUS,
 dmsl@cs.ucy.ac.cy, http://dmsl.cs.ucy.ac.cy/

 Permission is hereby granted, free of charge, to any person obtaining a copy
 of this software and associated documentation files (the "Software"), to deal
 in the Software without restriction, including without limitation the rights
 to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 copies of the Software, and to permit persons to whom the Software is
 furnished to do so, subject to the following conditions:

 The above copyright notice and this permission notice shall be included in
 all copies or substantial portions of the Software.

 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 THE SOFTWARE.
 */

var app = angular.module('anyViewer', ['ngRoute', 'ui.bootstrap', 'ui.select', 'ngSanitize','angular-loading-bar']);

app.service('GMapService', function () {

    this.gmap = {};
    this.directionsDisplay = undefined;
    this.directionsService = undefined;
//    this.searchBox = {};

    var self = this;

    // Initialize Google Maps
    var mapOptions = {
        center: {lat: 30, lng: 0},
        minZoom: 2,
        zoom: 2,
        panControl: false,
        zoomControl: true,
        zoomControlOptions: {
            style: google.maps.ZoomControlStyle.LARGE,
            position: google.maps.ControlPosition.LEFT_CENTER
        },
        rotateControl: true,
        mapTypeControl: true,
        mapTypeControlOptions: {
            position: google.maps.ControlPosition.RIGHT_BOTTOM
        },
        scaleControl: true,
        streetViewControl: false,
        overviewMapControl: true
    };

    self.gmap = new google.maps.Map(document.getElementById('map-canvas'),
        mapOptions);

    var directionsService = new google.maps.DirectionsService();
    var directionsDisplay = new google.maps.DirectionsRenderer();

    self.calcRoute = function (start, end, callback) {
        if (!start || !end) {
            console.log("Invalid start or end point.");
            return;
        }

        var request = {
            origin: start,
            destination: end,
            travelMode: google.maps.TravelMode.DRIVING
        };

        directionsService.route(request, function (response, status) {
            if (status == google.maps.DirectionsStatus.OK) {
                directionsDisplay.setMap(self.gmap);
                directionsDisplay.setDirections(response);
                if (response && response.routes && response.routes.length && response.routes[0].overview_path && response.routes[0].overview_path.length) {
                    var len = response.routes[0].overview_path.length;
                    callback(response.routes[0].overview_path[len - 1]);
                } else {
                    callback(undefined);
                }
            } else {
                callback(undefined);
            }
        });
    };

    self.clearRoute = function () {
        if (directionsDisplay)
            directionsDisplay.setMap(null);
    };

    self.isRouteShown = function () {
        return directionsDisplay && directionsDisplay.getMap();
    };

    // Initialize search box for places
//    var input = (document.getElementById('pac-input'));
//    self.gmap.controls[google.maps.ControlPosition.TOP_LEFT].push(input);
//    self.searchBox = new google.maps.places.SearchBox((input));
//
//    google.maps.event.addListener(self.searchBox, 'places_changed', function () {
//        var places = self.searchBox.getPlaces();
//
//        if (places.length == 0) {
//            return;
//        }
//
//        self.gmap.panTo(places[0].geometry.location);
//        self.gmap.setZoom(17);
//    });

    // Bias the SearchBox results towards places that are within the bounds of the
    // current map's viewport.
//    self.gmap.addListener(self.gmap, 'bounds_changed', function () {
//        var bounds = self.gmap.getBounds();
//        searchBox.setBounds(bounds);
//    });

    // Add click listener on map to add a marker on click
    //google.maps.event.addListener(self.gmap, 'click', function (event) {
    //
    //    if (!self.mainMarker) {
    //        self.mainMarker = new google.maps.Marker({
    //            position: event.latLng,
    //            map: self.gmap,
    //            title: "Helper marker at (" + event.latLng.lat() + ", " + event.latLng.lng() + ")"
    //        });
    //    }
    //
    //    self.mainMarker.setPosition(event.latLng);
    //    self.mainMarker.setTitle("Helper marker at (" + event.latLng.lat() + ", " + event.latLng.lng() + ")");
    //});

});

app.factory('AnyplaceService', ['$rootScope', '$q', function ($rootScope, $q) {

    var anyService = {};
    anyService.BuildingsLoaded = true;
    anyService.selectedBuilding = undefined;
    anyService.selectedFloor = undefined;
    anyService.selectedPoi = undefined;
    anyService.selectedGenPoi = undefined;
    anyService.selectedSearchPoi = undefined;
    anyService.myBuildingSelected = undefined;
    anyService.selectedCampus = undefined;

    anyService.availableFloors = {};

    anyService.jsonReq = {
        username: 'username',
        password: 'password'
    };

    // notifications
    anyService.alerts = [];

    anyService.getBuilding = function () {
        return this.selectedBuilding;
    };

    anyService.getBuildingId = function () {
        if (!this.selectedBuilding) {
            return undefined;
        }
        return this.selectedBuilding.buid;
    };

    anyService.getBuildingName = function () {
        if (!this.selectedBuilding) {
            return 'N/A';
        }
        return this.selectedBuilding.name;
    };

    anyService.getFloor = function () {
        return this.selectedFloor;
    };

    anyService.getFloorNumber = function () {
        if (!this.selectedFloor) {
            return 'N/A';
        }
        return String(this.selectedFloor.floor_number);
    };

    anyService.setBuilding = function (b) {
        this.selectedBuilding = b;
    };

    anyService.setFloor = function (f) {
        this.selectedFloor = f;
    };

    anyService.setSelectedFloorByNum = function (fnum) {
        this.selectedFloor = anyService.availableFloors[fnum];
    };

    anyService.addAlert = function (type, msg) {
        this.alerts[0] = ({msg: msg, type: type});

        var promise = $q(function(resolve, reject) {
            setTimeout(function () {
                resolve();
            }, 3500);
        });

        promise.then(function(){
            anyService.alerts.splice(0, 1);
        }, function(){});
    };

    anyService.closeAlert = function (index) {
        this.alerts.splice(index, 1);
    };

    anyService.getBuildingViewerUrl = function () {
        if (!this.selectedBuilding || !this.selectedBuilding.buid) {
            return "N/A";
        }
        return "https://anyplace.cs.ucy.ac.cy/viewer/?buid=" + this.selectedBuilding.buid;
    };

    anyService.getViewerUrl = function () {
        var baseUrl="";
        if (!this.selectedBuilding || !this.selectedBuilding.buid)
            return baseUrl;
        baseUrl += "&buid=" + this.selectedBuilding.buid;

        if (!this.selectedFloor || !this.selectedFloor.floor_number)
            return baseUrl;
        baseUrl += "&floor=" + this.selectedFloor.floor_number;

        if (!this.selectedPoi || !this.selectedPoi.puid)
            return baseUrl;
        baseUrl += "&selected=" + this.selectedPoi.puid;
        return baseUrl;
    };

    anyService.clearAllData = function () {
        anyService.selectedPoi = undefined;
        anyService.selectedFloor = undefined;
        anyService.selectedBuilding = undefined;
        anyService.selectedGenPoi = undefined;
    };

    return anyService;
}]);

app.factory('Alerter', function () {
    var alerter = {};

    alerter.AlertCtrl = '-';

    return alerter;
});

app.factory('formDataObject', function () {
    return function (data, headersGetter) {
        var formData = new FormData();
        angular.forEach(data, function (value, key) {
            formData.append(key, value);
        });

        var headers = headersGetter();
        delete headers['Content-Type'];
        return formData;
    };
});

app.config(['$locationProvider', function ($location) {
    //now there won't be a hashbang within URLs for browsers that support HTML5 history
    $location.html5Mode({
        enabled: true,
        requireBase: false
    });
}]);

app.config(['$routeProvider', function ($routeProvider) {
    $routeProvider
        .when('/#/b/:alias', {
            templateUrl: 'index.html',
            controller: 'ControlBarController',
            caseInsensitiveMatch: true
        })
        .otherwise({redirectTo: '/', caseInsensitiveMatch: true});
}]);

app.config(['cfpLoadingBarProvider', function(cfpLoadingBarProvider) {
    cfpLoadingBarProvider.includeSpinner = false;
}]);

app.filter('propsFilter', function () {
    return function (items, props) {
        var out = [];

        if (angular.isArray(items)) {
            items.forEach(function (item) {
                var itemMatches = false;

                var keys = Object.keys(props);
                for (var i = 0; i < keys.length; i++) {
                    var prop = keys[i];
                    var text = props[prop].toLowerCase();
                    if (item[prop].toString().toLowerCase().indexOf(text) !== -1) {
                        itemMatches = true;
                        break;
                    }
                }

                if (itemMatches) {
                    out.push(item);
                }
            });
        } else {
            // Let the output be the input untouched
            out = items;
        }

        return out;
    }
});
