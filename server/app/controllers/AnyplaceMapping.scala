/*
 * AnyPlace: A free and open Indoor Navigation Service with superb accuracy!
 *
 * Anyplace is a first-of-a-kind indoor information service offering GPS-less
 * localization, navigation and search inside buildings using ordinary smartphones.
 *
 * Author(s): Constantinos Costa, Kyriakos Georgiou, Lambros Petrou
 *
 * Supervisor: Demetrios Zeinalipour-Yazti
 *
 * URL: https://anyplace.cs.ucy.ac.cy
 * Contact: anyplace@cs.ucy.ac.cy
 *
 * Copyright (c) 2016, Data Management Systems Lab (DMSL), University of Cyprus.
 * All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the “Software”), to deal in the
 * Software without restriction, including without limitation the rights to use, copy,
 * modify, merge, publish, distribute, sublicense, and/or sell copies of the Software,
 * and to permit persons to whom the Software is furnished to do so, subject to the
 * following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS
 * OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 *
 */
package controllers

import datasources.DatasourceException
import datasources.ProxyDataSource
import db_models._
import oauth.provider.v2.models.OAuth2Request
import org.apache.commons.codec.binary.Base64
import play.api.mvc._
import utils._
import java.io._
import java.net.HttpURLConnection
import java.net.URL
import java.text.NumberFormat
import java.text.ParseException
import java.util
import java.util.Locale
import java.util.zip.GZIPOutputStream
import java.util.HashMap

import com.couchbase.client.java.document.json.{JsonArray, JsonObject}
import play.api.libs.json.{JsObject, JsString, Json}


object AnyplaceMapping extends play.api.mvc.Controller {

  private val ADMIN_ID = "112997031510415584062_google"

  private def verifyOwnerId(authToken: String): String = {
    //remove the double string qoutes due to json processing
    val gURL = "https://www.googleapis.com/oauth2/v3/tokeninfo?id_token=" + authToken.replace("\"\"", "\"")
    var res = ""
    try
      res = sendGet(gURL)
    catch {
      case e: Exception => {
        LPLogger.error(e.toString)
        null
      }
    }
    if (res != null)
      try {
        var json = JsonObject.fromJson(res)
        if (json.get("user_id") != null)
          return json.get("user_id").toString
        else if (json.get("sub") != null)
          return json.get("sub").toString
      } catch {
        case ioe: IOException => null
      }
    null
  }

  private def appendToOwnerId(ownerId: String) = ownerId + "_google"

  private def sendGet(url: String) = {
    val obj = new URL(url)
    val con = obj.openConnection().asInstanceOf[HttpURLConnection]
    con.setRequestMethod("GET")
    val responseCode = con.getResponseCode
    val in = new BufferedReader(new InputStreamReader(con.getInputStream))
    val response = new StringBuffer()
    response.append(Iterator.continually(in.readLine()).takeWhile(_ != null).mkString)
    in.close()
    response.toString
  }

  def getRadioHeatmap() = Action {
    implicit request =>

      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody()) AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplaceMapping::getRadioHeatmap(): " + json.toString)
      try {
        val radioPoints = ProxyDataSource.getIDatasource.getRadioHeatmap
        if (radioPoints == null) AnyResponseHelper.bad_request("Building does not exist or could not be retrieved!")
        val res = JsonObject.empty()
        res.put("radioPoints", (radioPoints))
        AnyResponseHelper.ok(res, "Successfully retrieved all radio points!")
      } catch {
        case e: DatasourceException => AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
  }

  def getRadioHeatmapByBuildingFloor() = Action {
    implicit request =>

      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody()) AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplaceMapping::getRadioHeatmap(): " + json.toString)
      val requiredMissing = JsonUtils.requirePropertiesInJson(json, "buid", "floor")
      if (!requiredMissing.isEmpty) AnyResponseHelper.requiredFieldsMissing(requiredMissing)
      val buid = (json \ "buid").toString
      val floor = (json \ "floor").toString
      try {
        val radioPoints = ProxyDataSource.getIDatasource.getRadioHeatmapByBuildingFloor(buid, floor)
        if (radioPoints == null) AnyResponseHelper.bad_request("Building does not exist or could not be retrieved!")
        val res = JsonObject.empty()
        res.put("radioPoints", radioPoints)
        try {
          gzippedJSONOk(res.toString)
        } catch {
          case ioe: IOException => AnyResponseHelper.ok(res, "Successfully retrieved all radio points!")
        }
      } catch {
        case e: DatasourceException => AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
  }


  def getRadioHeatmapBbox = Action {
    implicit request =>
      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody)
        AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplacePosition::radioDownloadFloor(): " + json.toString)
      val requiredMissing = JsonUtils.requirePropertiesInJson(json, "coordinates_lat", "coordinates_lon", "floor", "buid", "range")
      if (!requiredMissing.isEmpty)
        AnyResponseHelper.requiredFieldsMissing(requiredMissing)
      val lat = json.\\("coordinates_lat").mkString.replace("\"","")
      val lon = json.\\("coordinates_lon").mkString.replace("\"","")
      val floor_number = json.\\("floor").mkString.replace("\"","")
      val buid = json.\\("buid").mkString.replace("\"","")
      val strRange = json.\\("range").mkString.replace("\"","")
      val weight = json.\\("weight").mkString.replace("\"","")
      val range = strRange.toInt
      try {
        var radioPoints: util.List[JsonObject] = null
        if (weight.compareTo("false") == 0) radioPoints = ProxyDataSource.getIDatasource.getRadioHeatmapBBox2(lat, lon, buid, floor_number, range)
        else if (weight.compareTo("true") == 0) radioPoints = ProxyDataSource.getIDatasource.getRadioHeatmapBBox(lat, lon, buid, floor_number, range)
        else if (weight.compareTo("no spatial") == 0) radioPoints = ProxyDataSource.getIDatasource.getRadioHeatmapByBuildingFloor2(lat, lon, buid, floor_number, range)
        if (radioPoints == null)
          AnyResponseHelper.bad_request("Building does not exist or could not be retrieved!")
        val res = JsonObject.empty()
        res.put("radioPoints", radioPoints)
        try //                if (request().getHeader("Accept-Encoding") != null && request().getHeader("Accept-Encoding").contains("gzip")) {
        gzippedJSONOk(res.toString)
        //                }
        //                return AnyResponseHelper.ok(res.toString());
        catch {
          case ioe: IOException =>
            AnyResponseHelper.ok(res, "Successfully retrieved all radio points!")
        }
      } catch {
        case e: DatasourceException =>
          AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
  }

  def deleteRadiosInBox() = Action {
    implicit request =>

      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody()) AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplaceMapping::deleteRadiosInBox(): " + json.toString)
      try {
        if (!ProxyDataSource.getIDatasource.deleteRadiosInBox()) AnyResponseHelper.bad_request("Building already exists or could not be added!")
        AnyResponseHelper.ok("Success")
      } catch {
        case e: DatasourceException => AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
  }

  def buildingAdd() = Action {
    implicit request =>

      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody()) AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplaceMapping::buildingAdd(): " + json.toString)
      val requiredMissing = JsonUtils.requirePropertiesInJson(json, "is_published", "name", "description",
        "url", "address", "coordinates_lat", "coordinates_lon", "access_token")
      if (!requiredMissing.isEmpty) AnyResponseHelper.requiredFieldsMissing(requiredMissing)
      if ((json \ "access_token").get == null) AnyResponseHelper.forbidden("Unauthorized")
      var owner_id = verifyOwnerId((json \ "access_token").as[String])
      if (owner_id == null) AnyResponseHelper.forbidden("Unauthorized")
      owner_id = appendToOwnerId(owner_id.toString)
      json = json.as[JsObject] + ("owner_id" -> Json.toJson(owner_id))
      try {
        var building: Building = null
        try {
          building = new Building(JsonObject.fromJson(json.toString()))
        } catch {
          case e: NumberFormatException => AnyResponseHelper.bad_request("Building coordinates are invalid!")
        }
        if (!ProxyDataSource.getIDatasource.addJsonDocument(building.getId, 0, building.toCouchGeoJSON())) AnyResponseHelper.bad_request("Building already exists or could not be added!")
        val res = JsonObject.empty()
        res.put("buid", building.getId)
        AnyResponseHelper.ok(res, "Successfully added building!")
      } catch {
        case e: DatasourceException => AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
  }

  def buildingUpdateCoOwners() = Action {
    implicit request =>

      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody()) AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody()
      LPLogger.info("AnyplaceMapping::buildingUpdateCoOwners(): " + json.toString)
      val requiredMissing = JsonUtils.requirePropertiesInJson(json, "buid", "access_token", "co_owners")
      if (!requiredMissing.isEmpty) AnyResponseHelper.requiredFieldsMissing(requiredMissing)
      if (json.\\("access_token") == null) AnyResponseHelper.forbidden("Unauthorized")
      var owner_id = verifyOwnerId((json \ "access_token").as[String])
      if (owner_id == null) AnyResponseHelper.forbidden("Unauthorized")
      owner_id = appendToOwnerId(owner_id)
      json = json.as[JsObject] + ("owner_id" -> Json.toJson(owner_id))
      val buid = json.\\("buid").mkString.replace("\"","")
      try {
        val stored_building = ProxyDataSource.getIDatasource.getFromKeyAsJson(buid)
        if (stored_building == null) AnyResponseHelper.bad_request("Building does not exist or could not be retrieved!")
        if (!isBuildingOwner(stored_building, owner_id)) AnyResponseHelper.unauthorized("Unauthorized")
        val building = new Building(stored_building)
        if (!ProxyDataSource.getIDatasource.replaceJsonDocument(building.getId, 0, building.appendCoOwners(json))) AnyResponseHelper.bad_request("Building could not be updated!")
        AnyResponseHelper.ok("Successfully updated building!")
      } catch {
        case e: DatasourceException => AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
  }

  def buildingUpdateOwner() = Action {
    implicit request =>

      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody()) AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplaceMapping::buildingUpdateCoOwners(): " + json.toString)
      val requiredMissing = JsonUtils.requirePropertiesInJson(json, "buid", "access_token", "new_owner")
      if (!requiredMissing.isEmpty) AnyResponseHelper.requiredFieldsMissing(requiredMissing)
      if (json.\("access_token").get == null) AnyResponseHelper.forbidden("Unauthorized")
      var owner_id = verifyOwnerId((json \ "access_token").as[String])
      if (owner_id == null) AnyResponseHelper.forbidden("Unauthorized")
      owner_id = appendToOwnerId(owner_id)
      json = json.as[JsObject] + ("owner_id" -> Json.toJson(owner_id))
      val buid = json.\\("buid").mkString.replace("\"","")
      var newOwner = json.\\("new_owner").mkString.replace("\"","")
      newOwner = appendToOwnerId(newOwner)
      try {
        val stored_building = ProxyDataSource.getIDatasource.getFromKeyAsJson(buid)
        if (stored_building == null) AnyResponseHelper.bad_request("Building does not exist or could not be retrieved!")
        if (!isBuildingOwner(stored_building, owner_id)) AnyResponseHelper.unauthorized("Unauthorized")
        val building = new Building(stored_building)
        if (!ProxyDataSource.getIDatasource.replaceJsonDocument(building.getId, 0, building.changeOwner(newOwner))) AnyResponseHelper.bad_request("Building could not be updated!")
        AnyResponseHelper.ok("Successfully updated building!")
      } catch {
        case e: DatasourceException => AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
  }

  def buildingUpdate() = Action {
    implicit request =>

      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody()) AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplaceMapping::buildingUpdate(): " + json.toString)
      val requiredMissing = JsonUtils.requirePropertiesInJson(json, "buid", "access_token")
      if (!requiredMissing.isEmpty) AnyResponseHelper.requiredFieldsMissing(requiredMissing)
      if (json.\("access_token").get == null) AnyResponseHelper.forbidden("Unauthorized")
      var owner_id = verifyOwnerId((json \ "access_token").as[String])
      if (owner_id == null) AnyResponseHelper.forbidden("Unauthorized")
      owner_id = appendToOwnerId(owner_id)
      json = json.as[JsObject] + ("owner_id" -> Json.toJson(owner_id))
      val buid = json.\\("buid").mkString.replace("\"","")
      try {
        val stored_building = ProxyDataSource.getIDatasource.getFromKeyAsJson(buid)
        if (stored_building == null) AnyResponseHelper.bad_request("Building does not exist or could not be retrieved!")
        if (!isBuildingOwner(stored_building, owner_id)) AnyResponseHelper.unauthorized("Unauthorized")
        if (json.\("is_published").get != null) {
          val is_published = json.\\("is_published").mkString.replace("\"","")
          if (is_published == "true" || is_published == "false") stored_building.put("is_published", json.\\("is_published").mkString.replace("\"",""))
        }
        if (json.\("name").get != null) stored_building.put("name", json.\\("name").mkString.replace("\"",""))
        if (json.\("bucode").get != null) stored_building.put("bucode", json.\\("bucode").mkString.replace("\"",""))
        if (json.\("description").get != null) stored_building.put("description", json.\\("description").mkString.replace("\"",""))
        if (json.\("url") != null) stored_building.put("url", json.\\("url").mkString.replace("\"",""))
        if (json.\("address") != null) stored_building.put("address", json.\\("address").mkString.replace("\"",""))
        if (json.\("coordinates_lat").get != null) stored_building.put("coordinates_lat", json.\\("coordinates_lat").mkString.replace("\"",""))
        if (json.\("coordinates_lon").get != null) stored_building.put("coordinates_lon", json.\\("coordinates_lon").mkString.replace("\"",""))
        val building = new Building(stored_building)
        if (!ProxyDataSource.getIDatasource.replaceJsonDocument(building.getId, 0, building.toCouchGeoJSON())) AnyResponseHelper.bad_request("Building could not be updated!")
        AnyResponseHelper.ok("Successfully updated building!")
      } catch {
        case e: DatasourceException => AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
  }

  def buildingDelete() = Action {
    implicit request =>

      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody()) AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplaceMapping::buildingDelete(): " + json.toString)
      val requiredMissing = JsonUtils.requirePropertiesInJson(json, "buid", "access_token")
      if (!requiredMissing.isEmpty) AnyResponseHelper.requiredFieldsMissing(requiredMissing)
      if (json.\("access_token").get == null) AnyResponseHelper.forbidden("Unauthorized")
      var owner_id = verifyOwnerId((json \ "access_token").as[String])
      if (owner_id == null) AnyResponseHelper.forbidden("Unauthorized")
      owner_id = appendToOwnerId(owner_id)
      json = json.as[JsObject] + ("owner_id" -> Json.toJson(owner_id))
      val buid = json.\\("buid").mkString.replace("\"","")
      try {
        val stored_building = ProxyDataSource.getIDatasource.getFromKeyAsJson(buid).asInstanceOf[JsonObject]
        if (stored_building == null) AnyResponseHelper.bad_request("Building does not exist or could not be retrieved!")
        if (!isBuildingOwner(stored_building, owner_id)) AnyResponseHelper.unauthorized("Unauthorized")
      } catch {
        case e: DatasourceException => AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
      try {
        val all_items_failed = ProxyDataSource.getIDatasource.deleteAllByBuilding(buid)
        if (all_items_failed.size > 0) {
          val obj = JsonObject.empty()
          obj.put("ids", (all_items_failed))
          AnyResponseHelper.bad_request(obj, "Some items related to the deleted building could not be deleted: " +
            all_items_failed.size +
            " items.")
        }
      } catch {
        case e: DatasourceException => AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
      val filePath = AnyPlaceTilerHelper.getRootFloorPlansDirFor(buid)
      try {
        val buidfile = new File(filePath)
        if (buidfile.exists()) HelperMethods.recDeleteDirFile(buidfile)
      } catch {
        case e: IOException => AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "] while deleting floor plans." +
          "\nAll related information is deleted from the database!")
      }
      AnyResponseHelper.ok("Successfully deleted everything related to building!")
  }

  def buildingAll = Action {
    implicit request =>
      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody()) AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplaceMapping::buildingAll(): " + json.toString)
      try {

        val buildings = ProxyDataSource.getIDatasource.getAllBuildings
        val res = JsonObject.empty()
        res.put("buildings", buildings)
        try {
          gzippedJSONOk(res.toString)
        }

        catch {
          case ioe: IOException => AnyResponseHelper.ok(res, "Successfully retrieved all buildings!")
        }
      } catch {
        case e: DatasourceException => AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
  }

  def echo = Action { implicit request =>
    var response = Ok("Got request [" + request + "]")
    //    val anyReq = new OAuth2Request(request)
    //    if (!anyReq.assertJsonBody()) AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
    //    var json = anyReq.getJsonBody
    //    LPLogger.info("AnyplaceMapping::buildingAll(): " + json.toString)
    //    try {
    //      val buildings = ProxyDataSource.getIDatasource.getAllBuildings
    //      val res = JsonUtils.createObjectNode()
    //      res.put("buildings",(buildings))
    //      try {
    //        gzippedJSONOk(res.toString)
    //      }
    //
    //      catch {
    //        case ioe: IOException => AnyResponseHelper.ok(res, "Successfully retrieved all buildings!")
    //      }
    //    } catch {
    //      case e: DatasourceException => AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
    //    }
    response
  }

  def buildingGetOne() = Action {
    implicit request =>

      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody()) AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplaceMapping::buildingGet(): " + json.toString)
      val requiredMissing = JsonUtils.requirePropertiesInJson(json, "buid")
      if (!requiredMissing.isEmpty) AnyResponseHelper.requiredFieldsMissing(requiredMissing)
      val buid = json.\\("buid").mkString.replace("\"","")
      try {
        val building = ProxyDataSource.getIDatasource.getFromKeyAsJson(buid)
        if (building != null && building.get("buid") != null && building.get("coordinates_lat") != null &&
          building.get("coordinates_lon") != null &&
          building.get("owner_id") != null &&
          building.get("name") != null &&
          building.get("description") != null &&
          building.get("puid") == null &&
          building.get("floor_number") == null) {
          building.asInstanceOf[JsonObject].removeKey("owner_id")
          building.asInstanceOf[JsonObject].removeKey("co_owners")
          val res = JsonObject.empty()
          res.put("building", building)
          try {
            gzippedJSONOk(res.toString)
          } catch {
            case ioe: IOException => AnyResponseHelper.ok(res, "Successfully retrieved all buildings!")
          }
        }
        AnyResponseHelper.not_found("Building not found.")
      } catch {
        case e: DatasourceException => AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
  }

  def buildingAllByOwner() = Action {
    implicit request =>

      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody()) AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplaceMapping::buildingAll(): " + json.toString)
      val requiredMissing = JsonUtils.requirePropertiesInJson(json, "access_token")
      if (!requiredMissing.isEmpty) AnyResponseHelper.requiredFieldsMissing(requiredMissing)
      if (json.\("access_token").get == null) AnyResponseHelper.forbidden("Unauthorized")
      var owner_id = verifyOwnerId((json \ "access_token").as[String])
      if (owner_id == null) AnyResponseHelper.forbidden("Unauthorized")
      owner_id = appendToOwnerId(owner_id)
      json = json.as[JsObject] + ("owner_id" -> Json.toJson(owner_id))
      if (owner_id == null || owner_id.isEmpty) AnyResponseHelper.requiredFieldsMissing(requiredMissing)
      try {
        val buildings = ProxyDataSource.getIDatasource.getAllBuildingsByOwner(owner_id)
        val res = JsonObject.empty()
        res.put("buildings", (buildings))
        try {
          gzippedJSONOk(res.toString)
        } catch {
          case ioe: IOException => AnyResponseHelper.ok(res, "Successfully retrieved all buildings!")
        }
      } catch {
        case e: DatasourceException => AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
  }

  def buildingByBucode() = Action {
    implicit request =>

      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody()) AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplaceMapping::buildingAll(): " + json.toString)
      val requiredMissing = JsonUtils.requirePropertiesInJson(json, "bucode")
      if (!requiredMissing.isEmpty) AnyResponseHelper.requiredFieldsMissing(requiredMissing)
      val bucode = json.\\("bucode").mkString.replace("\"","")
      try {
        val buildings = ProxyDataSource.getIDatasource.getAllBuildingsByBucode(bucode)
        val res = JsonObject.empty()
        res.put("buildings", buildings)
        try {
          gzippedJSONOk(res.toString)
        }

        catch {
          case ioe: IOException => AnyResponseHelper.ok(res, "Successfully retrieved all buildings!")
        }
      } catch {
        case e: DatasourceException => AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
  }

  def buildingCoordinates() = Action {
    implicit request =>

      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody()) AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplaceMapping::buildingCoordinates(): " + json.toString)
      val requiredMissing = JsonUtils.requirePropertiesInJson(json, "coordinates_lat", "coordinates_lon")
      if (!requiredMissing.isEmpty) AnyResponseHelper.requiredFieldsMissing(requiredMissing)
      try {
        val buildings = ProxyDataSource.getIDatasource.getAllBuildingsNearMe(java.lang.Double.parseDouble(json.\\("coordinates_lat").mkString.replace("\"","")),
          java.lang.Double.parseDouble(json.\\("coordinates_lon").mkString.replace("\"","")))
        val res = JsonObject.empty()
        res.put("buildings", (buildings))
        try {
          gzippedJSONOk(res.toString)
        } catch {
          case ioe: IOException => AnyResponseHelper.ok(res, "Successfully retrieved all buildings near your position!")
        }
      } catch {
        case e: DatasourceException => AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
  }

  import datasources.DatasourceException
  import datasources.ProxyDataSource
  import oauth.provider.v2.models.OAuth2Request
  import utils.AnyResponseHelper
  import utils.JsonUtils
  import utils.LPLogger
  import java.io.IOException
  import java.util

  /**
    * Retrieve the building Set.
    *
    * @return
    */
  def buildingSetAll = Action {
    implicit request =>
      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody)
        AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplaceMapping::buildingSetAll(): " + json.toString)
      var cuid = request.getQueryString("cuid").orNull
      if (cuid == null) cuid = json.\("cuid").as[String].replace("\"","")
      try {
        val campus = ProxyDataSource.getIDatasource.getBuildingSet(cuid)
        val buildings = ProxyDataSource.getIDatasource.getAllBuildings
        val result = new util.ArrayList[JsonObject]
        var cuname = ""
        var greeklish = ""
        var i = 0
       for (i <-0 until campus.size ) {
          val temp = campus.get(i)
          var j = 0
         for ( j <-0 until temp.getArray("buids").size) {
            if (j == 0) cuname = temp.get("name").toString
            if (j == 0) greeklish = temp.get("greeklish").toString
            var k = 0
            for ( k <-0 until buildings.size) { //a
              val temp2 = buildings.get(k)
              if (temp2.get("buid").toString.compareTo(temp.getArray("buids").get(j).toString) == 0)
                result.add(temp2)
            }
          }

        }
        val res = JsonObject.empty()
        res.put("buildings", result)
        res.put("name", cuname)
        System.out.println(greeklish)
        if (greeklish == null) greeklish = "false"
        res.put("greeklish", greeklish)
        try //                if (request().getHeader("Accept-Encoding") != null && request().getHeader("Accept-Encoding").contains("gzip")) {
        gzippedJSONOk(res.toString)
        //                }
        //                return AnyResponseHelper.ok(res.toString());
        catch {
          case ioe: IOException =>
            AnyResponseHelper.ok(res, "Successfully retrieved all buildings Sets!")
        }
      } catch {
        case e: DatasourceException =>
          AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
  }

  /**
    * Adds a new building set to the database
    *
    * @return the newly created Building ID is included in the response if success
    */
  def buildingSetAdd = Action {
    implicit request =>
      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody)
        AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplaceMapping::buildingSetAdd(): " + json.toString)
      val requiredMissing = JsonUtils.requirePropertiesInJson(json, "description", "name", "buids", "greeklish")
      if (!requiredMissing.isEmpty)
        AnyResponseHelper.requiredFieldsMissing(requiredMissing)
      // get access token from url and check it against google's service
      if (json.\\("access_token") == null)
        AnyResponseHelper.forbidden("Unauthorized1")
      var owner_id = verifyOwnerId((json \ "access_token").as[String])
      if (owner_id == null)
        AnyResponseHelper.forbidden("Unauthorized2")
      owner_id = appendToOwnerId(owner_id)
      json = json.as[JsObject] + ("owner_id" -> Json.toJson(owner_id))
      try {
        val cuid = json.\\("cuid").mkString.replace("\"","")
        val campus = ProxyDataSource.getIDatasource.BuildingSetsCuids(cuid)
        if (campus) AnyResponseHelper.bad_request("Building set already exists!")
        else {
          var buildingset: BuildingSet = null
          try
            buildingset = new BuildingSet(json.asInstanceOf[JsonObject])
          catch {
            case e: NumberFormatException =>
              AnyResponseHelper.bad_request("Building coordinates are invalid!")
          }
          if (!ProxyDataSource.getIDatasource.addJsonDocument(buildingset.getId, 0, buildingset.toCouchGeoJSON))
            AnyResponseHelper.bad_request("Building set already exists or could not be added!")
          val res = JsonObject.empty()
          res.put("cuid", buildingset.getId)
          AnyResponseHelper.ok(res, "Successfully added building Set!")
        }
      } catch {
        case e: DatasourceException =>
          AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
  }


  /**
    * Update the building information. Building to update is specified by buid
    *
    * @return
    */
  def campusUpdate = Action {
    implicit request =>
      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody)
        AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplaceMapping::campusUpdate(): " + json.toString)
      val requiredMissing = JsonUtils.requirePropertiesInJson(json, "cuid", "access_token")
      if (!requiredMissing.isEmpty)
        AnyResponseHelper.requiredFieldsMissing(requiredMissing)
      // get access token from url and check it against google's service
      if (json.\\("access_token") == null)
        AnyResponseHelper.forbidden("Unauthorized")
      var owner_id = verifyOwnerId((json \ "access_token").as[String])
      if (owner_id == null)
        AnyResponseHelper.forbidden("Unauthorized")
      owner_id = appendToOwnerId(owner_id)
      json = json.as[JsObject] + ("owner_id" -> Json.toJson(owner_id))
      val cuid = json.\\("cuid").mkString.replace("\"","")
      try {
        val stored_campus = ProxyDataSource.getIDatasource().getFromKeyAsJson(cuid)
        if (stored_campus == null)
          AnyResponseHelper.bad_request("Campus does not exist or could not be retrieved!")
        if (!isCampusOwner(stored_campus, owner_id))
          AnyResponseHelper.unauthorized("Unauthorized")
        // check for values to update
        if (json.\\("name") != null) stored_campus.put("name", json.\\("name").mkString.replace("\"",""))
        if (json.\\("description") != null) stored_campus.put("description", json.\\("description").mkString.replace("\"",""))
        if (json.\\("cuidnew") != null) stored_campus.put("cuid", json.\\("cuidnew").mkString.replace("\"",""))
        val campus = new BuildingSet(stored_campus)
        if (!ProxyDataSource.getIDatasource().replaceJsonDocument(campus.getId(), 0, campus.toCouchGeoJSON()))
          AnyResponseHelper.bad_request("Campus could not be updated!")
        AnyResponseHelper.ok("Successfully updated campus!")
      } catch {
        case e: DatasourceException =>
          AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
  }


  import datasources.DatasourceException
  import datasources.ProxyDataSource
  import oauth.provider.v2.models.OAuth2Request
  import utils.AnyResponseHelper
  import utils.JsonUtils
  import utils.LPLogger
  import java.io.IOException

  def buildingsetAllByOwner = Action {
    implicit request =>
      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody)
        AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplaceMapping::buildingSetAll(): " + json.toString)
      val requiredMissing = JsonUtils.requirePropertiesInJson(json, "access_token")
      if (!requiredMissing.isEmpty)
        AnyResponseHelper.requiredFieldsMissing(requiredMissing)
      // get access token from url and check it against google's service
      if (json.\\("access_token") == null)
        AnyResponseHelper.forbidden("Unauthorized")
      var owner_id = verifyOwnerId((json \ "access_token").as[String])
      if (owner_id == null)
        AnyResponseHelper.forbidden("Unauthorized")
      owner_id = appendToOwnerId(owner_id)
      json = json.as[JsObject] + ("owner_id" -> Json.toJson(owner_id))
      if (owner_id == null || owner_id.isEmpty)
        AnyResponseHelper.requiredFieldsMissing(requiredMissing)
      try {
        val buildingsets = ProxyDataSource.getIDatasource().getAllBuildingsetsByOwner(owner_id)
        val res = JsonObject.empty()
        res.put("buildingsets", buildingsets)
        try //                if (request().getHeader("Accept-Encoding") != null && request().getHeader("Accept-Encoding").contains("gzip")) {
        gzippedJSONOk(res.toString)
        //                }
        //                return AnyResponseHelper.ok(res.toString());
        catch {
          case ioe: IOException =>
            AnyResponseHelper.ok(res, "Successfully retrieved all buildingsets!")
        }
      } catch {
        case e: DatasourceException =>
          AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
  }

  /**
    * Delete the campus specified by cuid.
    *
    * @return
    */
  def campusDelete = Action {
    implicit request =>
      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody)
        AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplaceMapping::campusDelete(): " + json.toString)
      val requiredMissing = JsonUtils.requirePropertiesInJson(json, "cuid", "access_token")
      if (!requiredMissing.isEmpty)
        AnyResponseHelper.requiredFieldsMissing(requiredMissing)
      // get access token from url and check it against google's service
      if (json.\\("access_token") == null)
        AnyResponseHelper.forbidden("Unauthorized")
      var owner_id = verifyOwnerId((json \ "access_token").as[String])
      if (owner_id == null)
        AnyResponseHelper.forbidden("Unauthorized")
      owner_id = appendToOwnerId(owner_id)
      json = json.as[JsObject] + ("owner_id" -> Json.toJson(owner_id))
      val cuid = json.\\("cuid").mkString.replace("\"","")
      try {
        val stored_campus = ProxyDataSource.getIDatasource().getFromKeyAsJson(cuid)
        if (stored_campus == null)
          AnyResponseHelper.bad_request("Campus does not exist or could not be retrieved!")
        if (!isCampusOwner(stored_campus, owner_id))
          AnyResponseHelper.unauthorized("Unauthorized")
        if (!ProxyDataSource.getIDatasource().deleteFromKey(cuid))
          AnyResponseHelper.internal_server_error("Server Internal Error while trying to delete Campus")
      } catch {
        case e: DatasourceException =>
          AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
      AnyResponseHelper.ok("Successfully deleted everything related to building!")
  }

  private def isCampusOwner(campus: JsonObject, userId: String): Boolean = { // Admin
    if (userId == ADMIN_ID)
      true
    // Check if owner
    if (campus != null && campus.get("owner_id") != null && campus.getString("owner_id").equals(userId))
      true
    false
  }

  def floorAdd() = Action {
    implicit request =>

      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody()) AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplaceMapping::floorAdd(): " + json.toString)
      val requiredMissing = JsonUtils.requirePropertiesInJson(json, "is_published", "buid", "floor_name",
        "description", "floor_number", "access_token")
      if (!requiredMissing.isEmpty) AnyResponseHelper.requiredFieldsMissing(requiredMissing)
      if (json.\("access_token").get == null) AnyResponseHelper.forbidden("Unauthorized")
      var owner_id = verifyOwnerId((json \ "access_token").as[String])
      if (owner_id == null) AnyResponseHelper.forbidden("Unauthorized")
      owner_id = appendToOwnerId(owner_id)
      json = json.as[JsObject] + ("owner_id" -> Json.toJson(owner_id))
      val buid = json.\\("buid").mkString.replace("\"","")
      try {
        val stored_building = ProxyDataSource.getIDatasource.getFromKeyAsJson(buid)
        if (stored_building == null) AnyResponseHelper.bad_request("Building does not exist or could not be retrieved!")
        if (!isBuildingOwner(stored_building, owner_id)) AnyResponseHelper.unauthorized("Unauthorized")
      } catch {
        case e: DatasourceException => AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
      val floor_number = json.\\("floor_number").mkString.replace("\"","")
      if (!Floor.checkFloorNumberFormat(floor_number)) AnyResponseHelper.bad_request("Floor number cannot contain whitespace!")
      try {
        val floor = new Floor(JsonObject.fromJson(json.toString()))
        if (!ProxyDataSource.getIDatasource.addJsonDocument(floor.getId, 0, floor.toValidCouchJson().toString)) AnyResponseHelper.bad_request("Floor already exists or could not be added!")
        AnyResponseHelper.ok("Successfully added floor " + floor_number + "!")
      } catch {
        case e: DatasourceException => AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
  }

  def floorUpdate() = Action {
    implicit request =>

      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody()) AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplaceMapping::floorUpdate(): " + json.toString)
      val requiredMissing = JsonUtils.requirePropertiesInJson(json, "buid", "floor_number", "access_token")
      if (!requiredMissing.isEmpty) AnyResponseHelper.requiredFieldsMissing(requiredMissing)
      if (json.\("access_token").get == null) AnyResponseHelper.forbidden("Unauthorized")
      var owner_id = verifyOwnerId((json \ "access_token").as[String])
      if (owner_id == null) AnyResponseHelper.forbidden("Unauthorized")
      owner_id = appendToOwnerId(owner_id)
      json = json.as[JsObject] + ("owner_id" -> Json.toJson(owner_id))
      val buid = json.\\("buid").mkString.replace("\"","")
      try {
        val stored_building = ProxyDataSource.getIDatasource.getFromKeyAsJson(buid)
        if (stored_building == null) AnyResponseHelper.bad_request("Building does not exist or could not be retrieved!")
        if (!isBuildingOwner(stored_building, owner_id)) AnyResponseHelper.unauthorized("Unauthorized")
      } catch {
        case e: DatasourceException => AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
      val floor_number = json.\\("floor_number").mkString.replace("\"","")
      if (!Floor.checkFloorNumberFormat(floor_number)) AnyResponseHelper.bad_request("Floor number cannot contain whitespace!")
      try {
        val fuid = Floor.getId(buid, floor_number)
        val stored_floor = ProxyDataSource.getIDatasource.getFromKeyAsJson(fuid)
        if (stored_floor == null) AnyResponseHelper.bad_request("Floor does not exist or could not be retrieved!")
        if (json.\("is_published").get != null) stored_floor.put("is_published", json.\\("is_published").mkString.replace("\"",""))
        if (json.\("floor_name").get != null) stored_floor.put("floor_name", json.\\("floor_name").mkString.replace("\"",""))
        if (json.\("description").get != null) stored_floor.put("description", json.\\("description").mkString.replace("\"",""))
        val floor = new Floor(stored_floor)
        if (!ProxyDataSource.getIDatasource.replaceJsonDocument(floor.getId, 0, floor.toValidCouchJson().toString)) AnyResponseHelper.bad_request("Floor could not be updated!")
        AnyResponseHelper.ok("Successfully updated floor!")
      } catch {
        case e: DatasourceException => AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
  }

  def floorDelete() = Action {
    implicit request =>

      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody()) AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplaceMapping::floorDelete(): " + json.toString)
      val requiredMissing = JsonUtils.requirePropertiesInJson(json, "buid", "floor_number", "access_token")
      if (!requiredMissing.isEmpty) AnyResponseHelper.requiredFieldsMissing(requiredMissing)
      if (json.\("access_token").get == null) AnyResponseHelper.forbidden("Unauthorized")
      var owner_id = verifyOwnerId((json \ "access_token").as[String])
      if (owner_id == null) AnyResponseHelper.forbidden("Unauthorized")
      owner_id = appendToOwnerId(owner_id)
      json = json.as[JsObject] + ("owner_id" -> Json.toJson(owner_id))
      val buid = json.\\("buid").mkString.replace("\"","")
      val floor_number = json.\\("floor_number").mkString.replace("\"","")
      try {
        val stored_building = ProxyDataSource.getIDatasource.getFromKeyAsJson(buid)
        if (stored_building == null) AnyResponseHelper.bad_request("Building does not exist or could not be retrieved!")
        if (!isBuildingOwner(stored_building, owner_id)) AnyResponseHelper.unauthorized("Unauthorized")
      } catch {
        case e: DatasourceException => AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
      try {
        val all_items_failed = ProxyDataSource.getIDatasource.deleteAllByFloor(buid, floor_number)
        if (all_items_failed.size > 0) {
          val obj = JsonObject.empty()
          obj.put("ids", all_items_failed)
          AnyResponseHelper.bad_request(obj, "Some items related to the deleted floor could not be deleted: " +
            all_items_failed.size +
            " items.")
        }
      } catch {
        case e: DatasourceException => AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
      val filePath = AnyPlaceTilerHelper.getFloorPlanFor(buid, floor_number)
      try {
        val floorfile = new File(filePath)
        if (floorfile.exists()) HelperMethods.recDeleteDirFile(floorfile)
      } catch {
        case e: IOException => AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "] while deleting floor plan." +
          "\nAll related information is deleted from the database!")
      }
      AnyResponseHelper.ok("Successfully deleted everything related to the floor!")
  }

  def floorAll() = Action {
    implicit request =>

      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody()) AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplaceMapping::floorAll(): " + json.toString)
      val requiredMissing = JsonUtils.requirePropertiesInJson(json, "buid")
      if (!requiredMissing.isEmpty) AnyResponseHelper.requiredFieldsMissing(requiredMissing)
      val buid = json.\\("buid").mkString.replace("\"","")
      try {
        val buildings = ProxyDataSource.getIDatasource.floorsByBuildingAsJson(buid)
        val res = JsonObject.empty()
        res.put("floors", buildings)
        try {
          gzippedJSONOk(res.toString)
        } catch {
          case ioe: IOException => AnyResponseHelper.ok(res, "Successfully retrieved all floors!")
        }
      } catch {
        case e: DatasourceException => AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
  }

  def poisAdd() = Action {
    implicit request =>

      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody()) AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplaceMapping::poisAdd(): " + json.toString)
      val requiredMissing = JsonUtils.requirePropertiesInJson(json, "is_published", "buid", "floor_name",
        "floor_number", "name", "pois_type", "is_door", "is_building_entrance", "coordinates_lat", "coordinates_lon",
        "access_token")
      if (!requiredMissing.isEmpty) AnyResponseHelper.requiredFieldsMissing(requiredMissing)
      if (json.\("access_token").get == null) AnyResponseHelper.forbidden("Unauthorized")
      var owner_id = verifyOwnerId((json \ "access_token").as[String])
      if (owner_id == null) AnyResponseHelper.forbidden("Unauthorized")
      owner_id = appendToOwnerId(owner_id)
      json = json.as[JsObject] + ("owner_id" -> Json.toJson(owner_id))
      val buid = json.\\("buid").mkString.replace("\"","")
      try {
        val stored_building = ProxyDataSource.getIDatasource.getFromKeyAsJson(buid)
        if (stored_building == null) AnyResponseHelper.bad_request("Building does not exist or could not be retrieved!")
        if (!isBuildingOwner(stored_building, owner_id) && !isBuildingCoOwner(stored_building, owner_id)) AnyResponseHelper.unauthorized("Unauthorized")
      } catch {
        case e: DatasourceException => AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
      try {
        val poi = new Poi(JsonObject.fromJson(json.toString()))
        if (!ProxyDataSource.getIDatasource.addJsonDocument(poi.getId, 0, poi.toCouchGeoJSON())) AnyResponseHelper.bad_request("Poi already exists or could not be added!")
        val res = JsonObject.empty()
        res.put("puid", poi.getId)
        AnyResponseHelper.ok(res, "Successfully added poi!")
      } catch {
        case e: DatasourceException => AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
  }

  /**
    * Adds a new poi category to the database
    *
    * @return the newly created cat ID is included in the response if success
    */
  def categoryAdd = Action {
    implicit request =>

      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody)
        AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplaceMapping::buildingSetAdd(): " + json.toString)
      val requiredMissing = JsonUtils.requirePropertiesInJson(json, "poistypeid", "poistype", "owner_id", "types")
      if (!requiredMissing.isEmpty)
        AnyResponseHelper.requiredFieldsMissing(requiredMissing)
      // get access token from url and check it against google's service
      if (json.\\("access_token") == null)
        AnyResponseHelper.forbidden("Unauthorized1")
      var owner_id = verifyOwnerId((json \ "access_token").as[String])
      if (owner_id == null)
        AnyResponseHelper.forbidden("Unauthorized2")
      owner_id = appendToOwnerId(owner_id)
      json = json.as[JsObject] + ("owner_id" -> Json.toJson(owner_id))
      try {
        var poiscategory: PoisCategory = null
        try
          poiscategory = new PoisCategory(json.asInstanceOf[JsonObject])
        catch {
          case e: NumberFormatException =>
            AnyResponseHelper.bad_request("Bad request!")
        }
        if (!ProxyDataSource.getIDatasource.addJsonDocument(poiscategory.getId, 0, poiscategory.toCouchGeoJSON))
          AnyResponseHelper.bad_request("Building set already exists or could not be added!")
        val res = JsonObject.empty()
        res.put("poistypeid", poiscategory.getId)
        AnyResponseHelper.ok(res, "Successfully added Pois Category!")
      } catch {
        case e: DatasourceException =>
          AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
  }

  def poisUpdate() = Action {
    implicit request =>

      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody()) AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplaceMapping::poisUpdate(): " + json.toString)
      val requiredMissing = JsonUtils.requirePropertiesInJson(json, "puid", "buid", "access_token")
      if (!requiredMissing.isEmpty) AnyResponseHelper.requiredFieldsMissing(requiredMissing)
      if (json.\("access_token").get == null) AnyResponseHelper.forbidden("Unauthorized")
      var owner_id = verifyOwnerId((json \ "access_token").as[String])
      if (owner_id == null) AnyResponseHelper.forbidden("Unauthorized")
      owner_id = appendToOwnerId(owner_id)
      json = json.as[JsObject] + ("owner_id" -> Json.toJson(owner_id))
      val puid = json.\\("puid").mkString.replace("\"","")
      val buid = json.\\("buid").mkString.replace("\"","")
      try {
        val stored_building = ProxyDataSource.getIDatasource.getFromKeyAsJson(buid)
        if (stored_building == null) AnyResponseHelper.bad_request("Building does not exist or could not be retrieved!")
        if (!isBuildingOwner(stored_building, owner_id) && !isBuildingCoOwner(stored_building, owner_id)) AnyResponseHelper.unauthorized("Unauthorized")
      } catch {
        case e: DatasourceException => AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
      try {
        val stored_poi = ProxyDataSource.getIDatasource.getFromKeyAsJson(puid)
        if (stored_poi == null) AnyResponseHelper.bad_request("Building does not exist or could not be retrieved!")
        if (json.\("is_published").get != null) {
          val is_published = json.\\("is_published").mkString.replace("\"","")
          if (is_published == "true" || is_published == "false") stored_poi.put("is_published", json.\\("is_published").mkString.replace("\"",""))
        }
        if (json.\("name").get != null) stored_poi.put("name", json.\\("name").mkString.replace("\"",""))
        if (json.\("description").get != null) stored_poi.put("description", json.\\("description").mkString.replace("\"",""))
        if (json.\("url").get != null) stored_poi.put("url", json.\\("url").mkString.replace("\"",""))
        if (json.\("pois_type").get != null) stored_poi.put("pois_type", json.\\("pois_type").mkString.replace("\"",""))
        if (json.\("is_door").get != null) {
          val is_door = json.\\("is_door").mkString.replace("\"","")
          if (is_door == "true" || is_door == "false") stored_poi.put("is_door", json.\\("is_door").mkString.replace("\"",""))
        }
        if (json.\("is_building_entrance").get != null) {
          val is_building_entrance = json.\\("is_building_entrance").mkString.replace("\"","")
          if (is_building_entrance == "true" || is_building_entrance == "false") stored_poi.put("is_building_entrance",
            json.\\("is_building_entrance").mkString.replace("\"",""))
        }
        if (json.\("image").get != null) stored_poi.put("image", json.\\("image").mkString.replace("\"",""))
        if (json.\("coordinates_lat").get != null) stored_poi.put("coordinates_lat", json.\\("coordinates_lat").mkString.replace("\"",""))
        if (json.\("coordinates_lon").get != null) stored_poi.put("coordinates_lon", json.\\("coordinates_lon").mkString.replace("\"",""))
        val poi = new Poi(stored_poi)
        if (!ProxyDataSource.getIDatasource.replaceJsonDocument(poi.getId, 0, poi.toCouchGeoJSON())) AnyResponseHelper.bad_request("Poi could not be updated!")
        AnyResponseHelper.ok("Successfully updated poi!")
      } catch {
        case e: DatasourceException => AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
  }

  def poisDelete() = Action {
    implicit request =>

      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody()) AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplaceMapping::poiDelete(): " + json.toString)
      val requiredMissing = JsonUtils.requirePropertiesInJson(json, "puid", "buid", "access_token")
      if (!requiredMissing.isEmpty) AnyResponseHelper.requiredFieldsMissing(requiredMissing)
      if (json.\("access_token").get == null) AnyResponseHelper.forbidden("Unauthorized")
      var owner_id = verifyOwnerId((json \ "access_token").as[String])
      if (owner_id == null) AnyResponseHelper.forbidden("Unauthorized")
      owner_id = appendToOwnerId(owner_id)
      json = json.as[JsObject] + ("owner_id" -> Json.toJson(owner_id))
      val buid = json.\\("buid").mkString.replace("\"","")
      val puid = json.\\("puid").mkString.replace("\"","")
      try {
        val stored_building = ProxyDataSource.getIDatasource.getFromKeyAsJson(buid)
        if (stored_building == null) AnyResponseHelper.bad_request("Building does not exist or could not be retrieved!")
        if (!isBuildingOwner(stored_building, owner_id) && !isBuildingCoOwner(stored_building, owner_id)) AnyResponseHelper.unauthorized("Unauthorized")
      } catch {
        case e: DatasourceException => AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
      try {
        val all_items_failed = ProxyDataSource.getIDatasource.deleteAllByPoi(puid)
        if (all_items_failed.size > 0) {
          val obj = JsonObject.empty()
          obj.put("ids", (all_items_failed))
          AnyResponseHelper.bad_request(obj, "Some items related to the deleted poi could not be deleted: " +
            all_items_failed.size +
            " items.")
        }
        AnyResponseHelper.ok("Successfully deleted everything related to the poi!")
      } catch {
        case e: DatasourceException => AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
  }

  def poisByFloor() = Action {
    implicit request =>

      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody()) AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplaceMapping::poisByFloor(): " + json.toString)
      val requiredMissing = JsonUtils.requirePropertiesInJson(json, "buid", "floor_number")
      if (!requiredMissing.isEmpty) AnyResponseHelper.requiredFieldsMissing(requiredMissing)
      val buid = json.\\("buid").mkString.replace("\"","")
      val floor_number = json.\\("floor_number").mkString.replace("\"","")
      try {
        val pois = ProxyDataSource.getIDatasource.poisByBuildingFloorAsJson(buid, floor_number)
        val res = JsonObject.empty()
        res.put("pois", (pois))
        try {
          gzippedJSONOk(res.toString)
        } catch {
          case ioe: IOException => AnyResponseHelper.ok(res, "Successfully retrieved all pois from floor " + floor_number +
            "!")
        }
      } catch {
        case e: DatasourceException => AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
  }

  def poisByBuid() = Action {
    implicit request =>

      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody()) AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplaceMapping::poisByBuid(): " + json.toString)
      val requiredMissing = JsonUtils.requirePropertiesInJson(json, "buid")
      if (!requiredMissing.isEmpty) AnyResponseHelper.requiredFieldsMissing(requiredMissing)
      val buid = json.\\("buid").mkString.replace("\"","")
      try {
        val pois = ProxyDataSource.getIDatasource.poisByBuildingAsJson(buid)
        val res = JsonObject.empty()
        res.put("pois", (pois))
        try {
          gzippedJSONOk(res.toString)
        } catch {
          case ioe: IOException => AnyResponseHelper.ok(res, "Successfully retrieved all pois from building.")
        }
      } catch {
        case e: DatasourceException => AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
  }

  /**
    * Retrieve all the pois of a cuid combination.
    *
    * @return
    */
  def poisAll = Action {
    implicit request =>

      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody)
        AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      var cuid = request.getQueryString("cuid").orNull
      if (cuid == null) cuid = json.\\("cuid").mkString.replace("\"","")
      var letters = request.getQueryString("letters").orNull
      if (letters == null) letters = json.\\("letters").mkString.replace("\"","")
      var buid = request.getQueryString("buid").orNull
      if (buid == null) buid = json.\\("buid").mkString.replace("\"","")
      var greeklish = request.getQueryString("greeklish").orNull
      if (greeklish == null) greeklish = json.\\("greeklish").mkString.replace("\"","")
      try {
        var result: util.List[JsonObject] = new util.ArrayList[JsonObject]
        if (cuid.compareTo("") == 0) result = ProxyDataSource.getIDatasource.poisByBuildingAsJson3(buid, letters)
        else if (greeklish.compareTo("true") == 0) result = ProxyDataSource.getIDatasource.poisByBuildingAsJson2GR(cuid, letters)
        else result = ProxyDataSource.getIDatasource.poisByBuildingAsJson2(cuid, letters)
        val res = JsonObject.empty()
        res.put("pois", result)
        try
          gzippedJSONOk(res.toString)
        catch {
          case ioe: IOException =>
            AnyResponseHelper.ok(res, "Successfully retrieved all pois from building.")
        }
      } catch {
        case e: DatasourceException =>
          AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
  }


  /**
    * Retrieve all the pois of a building/floor combination.
    *
    * @return
    */
  def poisByBuidincConnectors = Action {
    implicit request =>

      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody)
        AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplaceMapping::poisByBuidincConnectors(): " + json.toString)
      val requiredMissing = JsonUtils.requirePropertiesInJson(json, "buid")
      if (!requiredMissing.isEmpty)
        AnyResponseHelper.requiredFieldsMissing(requiredMissing)
      val buid = json.\\("buid").mkString.replace("\"","")
      try {
        val pois = ProxyDataSource.getIDatasource.poisByBuildingIDAsJson(buid)
        val res = JsonObject.empty()
        res.put("pois", pois)
        try //                if (request().getHeader("Accept-Encoding") != null && request().getHeader("Accept-Encoding").contains("gzip")) {
        gzippedJSONOk(res.toString)
        //                }
        //                return AnyResponseHelper.ok(res.toString());
        catch {
          case ioe: IOException =>
            AnyResponseHelper.ok(res, "Successfully retrieved all pois from buid " + buid + "!")
        }
      } catch {
        case e: DatasourceException =>
          AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
  }

  /**
    * Retrieve all the pois types by owner.
    *
    * @return
    */
  def poisTypes = Action {
    implicit request =>
      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody)
        AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplaceMapping::poisTypes(): " + json.toString)
      val requiredMissing = JsonUtils.requirePropertiesInJson(json, "access_token")
      if (!requiredMissing.isEmpty)
        AnyResponseHelper.requiredFieldsMissing(requiredMissing)
      // get access token from url and check it against google's service
      if (json.\\("access_token") == null)
        AnyResponseHelper.forbidden("Unauthorized")
      var owner_id = verifyOwnerId((json \ "access_token").as[String])
      if (owner_id == null)
        AnyResponseHelper.forbidden("Unauthorized")
      owner_id = appendToOwnerId(owner_id)
      json = json.as[JsObject] + ("owner_id" -> Json.toJson(owner_id))
      if (owner_id == null || owner_id.isEmpty)
        AnyResponseHelper.requiredFieldsMissing(requiredMissing)
      try {
        val poistypes = ProxyDataSource.getIDatasource.getAllPoisTypesByOwner(owner_id)
        val res = JsonObject.empty()
        res.put("poistypes", poistypes)
        try //                if (request().getHeader("Accept-Encoding") != null && request().getHeader("Accept-Encoding").contains("gzip")) {
        gzippedJSONOk(res.toString)
        //                }
        //                return AnyResponseHelper.ok(res.toString());
        catch {
          case ioe: IOException =>
            AnyResponseHelper.ok(res, "Successfully retrieved all poistypes!")
        }
      } catch {
        case e: DatasourceException =>
          AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
  }

  def connectionAdd() = Action {
    implicit request =>

      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody()) AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplaceMapping::connectionAdd(): " + json.toString)
      val requiredMissing = JsonUtils.requirePropertiesInJson(json, "is_published", "pois_a", "floor_a",
        "buid_a", "pois_b", "floor_b", "buid_b", "buid", "edge_type", "access_token")
      if (!requiredMissing.isEmpty) AnyResponseHelper.requiredFieldsMissing(requiredMissing)
      if (json.\("access_token").get == null) AnyResponseHelper.forbidden("Unauthorized")
      var owner_id = verifyOwnerId((json \ "access_token").as[String])
      if (owner_id == null) AnyResponseHelper.forbidden("Unauthorized")
      owner_id = appendToOwnerId(owner_id)
      json = json.as[JsObject] + ("owner_id" -> Json.toJson(owner_id))
      val buid1 = json.\\("buid_a").mkString.replace("\"","")
      val buid2 = json.\\("buid_b").mkString.replace("\"","")
      try {
        var stored_building = ProxyDataSource.getIDatasource.getFromKeyAsJson(buid1)
        if (stored_building == null) AnyResponseHelper.bad_request("Building does not exist or could not be retrieved!")
        if (!isBuildingOwner(stored_building, owner_id) && !isBuildingCoOwner(stored_building, owner_id)) AnyResponseHelper.unauthorized("Unauthorized")
        stored_building = ProxyDataSource.getIDatasource.getFromKeyAsJson(buid2)
        if (stored_building == null) AnyResponseHelper.bad_request("Building does not exist or could not be retrieved!")
        if (!isBuildingOwner(stored_building, owner_id) && !isBuildingCoOwner(stored_building, owner_id)) AnyResponseHelper.unauthorized("Unauthorized")
      } catch {
        case e: DatasourceException => AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
      val edge_type = json \\ ("edge_type").mkString.replace("\"","")
      if (edge_type != Connection.EDGE_TYPE_ELEVATOR && edge_type != Connection.EDGE_TYPE_HALLWAY &&
        edge_type != Connection.EDGE_TYPE_ROOM &&
        edge_type != Connection.EDGE_TYPE_OUTDOOR &&
        edge_type != Connection.EDGE_TYPE_STAIR) AnyResponseHelper.bad_request("Invalid edge type specified.")
      val pois_a = json.\\("pois_a").mkString.replace("\"","")
      val pois_b = json.\\("pois_b").mkString.replace("\"","")
      try {
        val weight = calculateWeightOfConnection(pois_a, pois_b)
        JsonObject.fromJson(json.toString()).put("weight", java.lang.Double.toString(weight))
        if (edge_type == Connection.EDGE_TYPE_ELEVATOR || edge_type == Connection.EDGE_TYPE_STAIR) {
        }
        val conn = new Connection(JsonObject.fromJson(json.toString()))
        if (!ProxyDataSource.getIDatasource.addJsonDocument(conn.getId, 0, conn.toValidCouchJson().toString)) AnyResponseHelper.bad_request("Connection already exists or could not be added!")
        val res = JsonObject.empty()
        res.put("cuid", conn.getId)
        AnyResponseHelper.ok(res, "Successfully added new connection!")
      } catch {
        case e: DatasourceException => AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
  }

  def connectionUpdate() = Action {
    implicit request =>

      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody()) AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplaceMapping::connectionUpdate(): " + json.toString)
      val requiredMissing = JsonUtils.requirePropertiesInJson(json, "pois_a", "pois_b", "buid_a", "buid_b",
        "access_token")
      if (!requiredMissing.isEmpty) AnyResponseHelper.requiredFieldsMissing(requiredMissing)
      if (json.\("access_token").get == null) AnyResponseHelper.forbidden("Unauthorized")
      var owner_id = verifyOwnerId((json \ "access_token").as[String])
      if (owner_id == null) AnyResponseHelper.forbidden("Unauthorized")
      owner_id = appendToOwnerId(owner_id)
      json = json.as[JsObject] + ("owner_id" -> Json.toJson(owner_id))
      val buid1 = json.\\("buid_a").mkString.replace("\"","")
      val buid2 = json.\\("buid_b").mkString.replace("\"","")
      try {
        var stored_building = ProxyDataSource.getIDatasource.getFromKeyAsJson(buid1)
        if (stored_building == null) AnyResponseHelper.bad_request("Building does not exist or could not be retrieved!")
        if (!isBuildingOwner(stored_building, owner_id) && !isBuildingCoOwner(stored_building, owner_id)) AnyResponseHelper.unauthorized("Unauthorized")
        stored_building = ProxyDataSource.getIDatasource.getFromKeyAsJson(buid2)
        if (stored_building == null) AnyResponseHelper.bad_request("Building does not exist or could not be retrieved!")
        if (!isBuildingOwner(stored_building, owner_id) && !isBuildingCoOwner(stored_building, owner_id)) AnyResponseHelper.unauthorized("Unauthorized")
      } catch {
        case e: DatasourceException => AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
      try {
        val pois_a = json.\\("pois_a").mkString.replace("\"","")
        val pois_b = json.\\("pois_b").mkString.replace("\"","")
        val cuid = Connection.getId(pois_a, pois_b)
        val stored_conn = ProxyDataSource.getIDatasource.getFromKeyAsJson(cuid)
        if (stored_conn == null) AnyResponseHelper.bad_request("Connection does not exist or could not be retrieved!")
        if (json.\("is_published").get != null) {
          val is_published = json.\\("is_published").mkString.replace("\"","")
          if (is_published == "true" || is_published == "false") stored_conn.put("is_published", json.\\("is_published").mkString.replace("\"",""))
        }
        if (json.\("edge_type").get != null) {
          val edge_type = json.\\("edge_type").mkString.replace("\"","")
          if (edge_type != Connection.EDGE_TYPE_ELEVATOR && edge_type != Connection.EDGE_TYPE_HALLWAY &&
            edge_type != Connection.EDGE_TYPE_ROOM &&
            edge_type != Connection.EDGE_TYPE_OUTDOOR &&
            edge_type != Connection.EDGE_TYPE_STAIR) AnyResponseHelper.bad_request("Invalid edge type specified.")
          stored_conn.put("edge_type", edge_type)
        }
        val conn = new Connection(stored_conn)
        if (!ProxyDataSource.getIDatasource.replaceJsonDocument(conn.getId, 0, conn.toValidCouchJson().toString)) AnyResponseHelper.bad_request("Connection could not be updated!")
        AnyResponseHelper.ok("Successfully updated connection!")
      } catch {
        case e: DatasourceException => AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
  }

  def connectionDelete() = Action {
    implicit request =>

      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody()) AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplaceMapping::poiDelete(): " + json.toString)
      val requiredMissing = JsonUtils.requirePropertiesInJson(json, "pois_a", "pois_b", "buid_a", "buid_b",
        "access_token")
      if (!requiredMissing.isEmpty) AnyResponseHelper.requiredFieldsMissing(requiredMissing)
      if (json.\("access_token").get == null) AnyResponseHelper.forbidden("Unauthorized")
      var owner_id = verifyOwnerId((json \ "access_token").as[String])
      if (owner_id == null) AnyResponseHelper.forbidden("Unauthorized")
      owner_id = appendToOwnerId(owner_id)
      json = json.as[JsObject] + ("owner_id" -> Json.toJson(owner_id))
      val buid1 = json.\\("buid_a").mkString.replace("\"","")
      val buid2 = json.\\("buid_b").mkString.replace("\"","")
      try {
        var stored_building = ProxyDataSource.getIDatasource.getFromKeyAsJson(buid1)
        if (stored_building == null) AnyResponseHelper.bad_request("Building does not exist or could not be retrieved!")
        if (!isBuildingOwner(stored_building, owner_id) && !isBuildingCoOwner(stored_building, owner_id)) AnyResponseHelper.unauthorized("Unauthorized")
        stored_building = ProxyDataSource.getIDatasource.getFromKeyAsJson(buid2)
        if (stored_building == null) AnyResponseHelper.bad_request("Building does not exist or could not be retrieved!")
        if (!isBuildingOwner(stored_building, owner_id) && !isBuildingCoOwner(stored_building, owner_id)) AnyResponseHelper.unauthorized("Unauthorized")
      } catch {
        case e: DatasourceException => AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
      val pois_a = json.\\("pois_a").mkString.replace("\"","")
      val pois_b = json.\\("pois_b").mkString.replace("\"","")
      try {
        val cuid = Connection.getId(pois_a, pois_b)
        val all_items_failed = ProxyDataSource.getIDatasource.deleteAllByConnection(cuid)
        if (all_items_failed == null) {
          LPLogger.info("AnyplaceMapping::connectionDelete(): " + cuid + " not found.")
          AnyResponseHelper.bad_request("POI Connection not found")
        }
        if (all_items_failed.size > 0) {
          val obj = JsonObject.empty()
          obj.put("ids", (all_items_failed))
          AnyResponseHelper.bad_request(obj, "Some items related to the deleted connection could not be deleted: " +
            all_items_failed.size +
            " items.")
        }
        AnyResponseHelper.ok("Successfully deleted everything related to the connection!")
      } catch {
        case e: DatasourceException => AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
  }

  def connectionsByFloor() = Action {
    implicit request =>

      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody()) AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplaceMapping::poisByFloor(): " + json.toString)
      val requiredMissing = JsonUtils.requirePropertiesInJson(json, "buid", "floor_number")
      if (!requiredMissing.isEmpty) AnyResponseHelper.requiredFieldsMissing(requiredMissing)
      val buid = json.\\("buid").mkString.replace("\"","")
      val floor_number = json.\\("floor_number").mkString.replace("\"","")
      try {
        val pois = ProxyDataSource.getIDatasource.connectionsByBuildingFloorAsJson(buid, floor_number)
        val res = JsonObject.empty()
        res.put("connections", (pois))
        try {
          gzippedJSONOk(res.toString)
        } catch {
          case ioe: IOException => AnyResponseHelper.ok(res, "Successfully retrieved all pois from floor " + floor_number +
            "!")
        }
      } catch {
        case e: DatasourceException => AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
  }

  import datasources.DatasourceException
  import datasources.ProxyDataSource
  import oauth.provider.v2.models.OAuth2Request
  import utils.AnyResponseHelper
  import utils.JsonUtils
  import utils.LPLogger
  import java.io.IOException

  /**
    * Retrieve all the pois of a building/floor combination.
    *
    * @return
    */
  def connectionsByallFloors = Action {
    implicit request =>

      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody)
        AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplaceMapping::connectionsByallFloors(): " + json.toString)
      val requiredMissing = JsonUtils.requirePropertiesInJson(json, "buid")
      if (!requiredMissing.isEmpty)
        AnyResponseHelper.requiredFieldsMissing(requiredMissing)
      val buid = json.\\("buid").mkString.replace("\"","")
      try {
        val pois = ProxyDataSource.getIDatasource.connectionsByBuildingAllFloorsAsJson(buid)
        val res = JsonObject.empty()
        res.put("connections", pois)
        try //                if (request().getHeader("Accept-Encoding") != null && request().getHeader("Accept-Encoding").contains("gzip")) {
        gzippedJSONOk(res.toString)
        //                }
        //                return AnyResponseHelper.ok(res.toString());
        catch {
          case ioe: IOException =>
            AnyResponseHelper.ok(res, "Successfully retrieved all pois from all floors !")
        }
      } catch {
        case e: DatasourceException =>
          AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
  }

  private def calculateWeightOfConnection(pois_a: String, pois_b: String) = {
    var lat_a = 0.0
    var lon_a = 0.0
    var lat_b = 0.0
    var lon_b = 0.0
    val nf = NumberFormat.getInstance(Locale.ENGLISH)
    val pa = ProxyDataSource.getIDatasource.getFromKeyAsJson(pois_a)
    if (pa == null) {
      lat_a = 0.0
      lon_a = 0.0
    } else try {
      lat_a = nf.parse(pa.getString("coordinates_lat")).doubleValue()
      lon_a = nf.parse(pa.getString("coordinates_lon")).doubleValue()
    } catch {
      case e: ParseException => e.printStackTrace()
    }
    val pb = ProxyDataSource.getIDatasource.getFromKeyAsJson(pois_b)
    if (pb == null) {
      lat_b = 0.0
      lon_b = 0.0
    } else try {
      lat_b = nf.parse(pb.getString("coordinates_lat")).doubleValue()
      lon_b = nf.parse(pb.getString("coordinates_lon")).doubleValue()
    } catch {
      case e: ParseException => e.printStackTrace()
    }
    GeoPoint.getDistanceBetweenPoints(lat_a, lon_a, lat_b, lon_b, "K")
  }

  def serveFloorPlanBinary(buid: String, floor_number: String) = Action {
    implicit request =>

      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody()) AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplaceMapping::serveFloorPlan(): " + json.toString)
      val filePath = AnyPlaceTilerHelper.getFloorPlanFor(buid, floor_number)
      LPLogger.info("requested: " + filePath)
      try {
        val file = new File(filePath)
        if (!file.exists() || !file.canRead()) AnyResponseHelper.bad_request("Requested floor plan does not exist or cannot be read! (" +
          floor_number +
          ")")
        Ok.sendFile(file)
      } catch {
        case e: FileNotFoundException => AnyResponseHelper.internal_server_error("Could not read floor plan.")
      }
  }

  def serveFloorPlanTilesZip(buid: String, floor_number: String) = Action {
    implicit request =>

      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody()) AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplaceMapping::serveFloorPlanTilesZip(): " + json.toString)
      if (!Floor.checkFloorNumberFormat(floor_number)) AnyResponseHelper.bad_request("Floor number cannot contain whitespace!")
      val filePath = AnyPlaceTilerHelper.getFloorTilesZipFor(buid, floor_number)
      LPLogger.info("requested: " + filePath)
      try {
        val file = new File(filePath)
        if (!file.exists() || !file.canRead()) AnyResponseHelper.bad_request("Requested floor plan does not exist or cannot be read! (" +
          floor_number +
          ")")
        Ok.sendFile(file)
      } catch {
        case e: FileNotFoundException => AnyResponseHelper.internal_server_error("Could not read floor plan.")
      }
  }

  def serveFloorPlanTilesZipLink(buid: String, floor_number: String) = Action {
    implicit request =>

      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody()) AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplaceMapping::serveFloorPlanTilesZipLink(): " + json.toString)
      if (!Floor.checkFloorNumberFormat(floor_number)) AnyResponseHelper.bad_request("Floor number cannot contain whitespace!")
      val filePath = AnyPlaceTilerHelper.getFloorTilesZipFor(buid, floor_number)
      LPLogger.info("requested: " + filePath)
      val file = new File(filePath)
      if (!file.exists() || !file.canRead()) AnyResponseHelper.bad_request("Requested floor plan does not exist or cannot be read! (" +
        floor_number +
        ")")
      val res = JsonObject.empty()
      res.put("tiles_archive", AnyPlaceTilerHelper.getFloorTilesZipLinkFor(buid, floor_number))
      AnyResponseHelper.ok(res, "Successfully fetched link for the tiles archive!")
  }

  def serveFloorPlanTilesStatic(buid: String, floor_number: String, path: String) = Action {
    LPLogger.info("AnyplaceMapping::serveFloorPlanTilesStatic(): " + buid +
      ":" +
      floor_number +
      ":" +
      path)
    if (path == null || buid == null || floor_number == null ||
      path.trim().isEmpty ||
      buid.trim().isEmpty ||
      floor_number.trim().isEmpty) NotFound(<h1>Page not found</h1>)
    var filePath: String = null
    filePath = if (path == AnyPlaceTilerHelper.FLOOR_TILES_ZIP_NAME) AnyPlaceTilerHelper.getFloorTilesZipFor(buid,
      floor_number) else AnyPlaceTilerHelper.getFloorTilesDirFor(buid, floor_number) +
      path
    LPLogger.info("static requested: " + filePath)
    try {
      val file = new File(filePath)
      if (!file.exists() || !file.canRead()) AnyResponseHelper.not_found("File requested not found")
      Ok.sendFile(file)
    } catch {
      case e: FileNotFoundException => AnyResponseHelper.internal_server_error("Could not read floor plan.")
    }
  }

  def serveFloorPlanBase64(buid: String, floor_number: String) = Action {
    implicit request =>

      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody()) AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplaceMapping::serveFloorPlanBase64(): " + json.toString)
      val filePath = AnyPlaceTilerHelper.getFloorPlanFor(buid, floor_number)
      LPLogger.info("requested: " + filePath)
      val file = new File(filePath)
      try {
        if (!file.exists() || !file.canRead()) AnyResponseHelper.bad_request("Requested floor plan does not exist or cannot be read! (" +
          floor_number +
          ")")
        try {
          val s = encodeFileToBase64Binary(filePath)
          try {
            gzippedOk(s)
          } catch {
            case ioe: IOException => Ok(s)
          }
        } catch {
          case e: IOException => AnyResponseHelper.bad_request("Requested floor plan cannot be encoded in base64 properly! (" +
            floor_number +
            ")")
        }
      } catch {
        case e: Exception => AnyResponseHelper.internal_server_error("Unknown server error during floor plan delivery!")
      }
  }


  /**
    * Returns the floorplan in base64 form. Used by the Anyplace websites
    *
    * @param buid
    * @param floor_number
    * @return
    */
  def serveFloorPlanBase64all(buid: String, floor_number: String) = Action {
    implicit request =>
      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody)
        AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplaceMapping::serveFloorPlanBase64all(): " + json.toString + " " + floor_number)
      val floors = floor_number.split(" ")
      val all_floors = new util.ArrayList[String]
      var z = 0
      while ( {
        z < floors.length
      }) {
        val filePath = AnyPlaceTilerHelper.getFloorPlanFor(buid, floors(z))
        LPLogger.info("requested: " + filePath)
        val file = new File(filePath)
        try
            if (!file.exists || !file.canRead) {
              all_floors.add("")
            }
            else try {
              val s = encodeFileToBase64Binary(filePath)
              all_floors.add(s)
            } catch {
              case e: IOException =>
                AnyResponseHelper.bad_request("Requested floor plan cannot be encoded in base64 properly! (" + floors(z) + ")")
            }
        catch {
          case e: Exception =>
            AnyResponseHelper.internal_server_error("Unknown server error during floor plan delivery!")
        }

        {
          z += 1;
          z - 1
        }
      }
      val res = JsonObject.empty()
      res.put("all_floors", all_floors)
      try
        gzippedJSONOk(res.toString)
      catch {
        case ioe: IOException =>
          AnyResponseHelper.ok(res, "Successfully retrieved all floors!")
      }
  }

  private def encodeFileToBase64Binary(fileName: String) = {
    val file = new File(fileName)
    val bytes = loadFile(file)
    val encoded = Base64.encodeBase64(bytes)
    val encodedString = new String(encoded)
    encodedString
  }

  private def loadFile(file: File) = {
    val is = new FileInputStream(file)
    val length = file.length
    if (length > java.lang.Integer.MAX_VALUE) {
    }
    val bytes = Array.ofDim[Byte](length.toInt)
    var offset = 0
    var numRead = 0
    do {
      numRead = is.read(bytes, offset, bytes.length - offset)
      offset += numRead
    } while ((offset < bytes.length && numRead >= 0))
    if (offset < bytes.length) throw new IOException("Could not completely read file " + file.getName)
    is.close()
    bytes
  }

  def floorPlanUpload() = Action {
    implicit request =>

      val anyReq = new OAuth2Request(request)
      val body = anyReq.getMultipartFormData
      if (body == null) AnyResponseHelper.bad_request("Invalid request type - Not Multipart!")
      var floorplan = body.file("floorplan").get
      if (floorplan == null) AnyResponseHelper.bad_request("Cannot find the floor plan file in your request!")
      val urlenc = body.asFormUrlEncoded
      val json_str = urlenc.get("json").get(0)
      if (json_str == null) AnyResponseHelper.bad_request("Cannot find json in the request!")
      var json: JsonObject = null
      try {
        json = JsonObject.fromJson(json_str)
      } catch {
        case e: IOException => AnyResponseHelper.bad_request("Cannot parse json in the request!")
      }
      LPLogger.info("Floorplan Request[json]: " + json.toString)
      LPLogger.info("Floorplan Request[floorplan]: " + floorplan.filename)
      val requiredMissing = JsonUtils.requirePropertiesInJson(json, "buid", "floor_number", "bottom_left_lat",
        "bottom_left_lng", "top_right_lat", "top_right_lng")
      if (!requiredMissing.isEmpty) AnyResponseHelper.requiredFieldsMissing(requiredMissing)
      val buid = json.getString("buid")
      val floor_number = json.getString("floor_number")
      val bottom_left_lat = json.getString("bottom_left_lat")
      val bottom_left_lng = json.getString("bottom_left_lng")
      val top_right_lat = json.getString("top_right_lat")
      val top_right_lng = json.getString("top_right_lng")
      val fuid = Floor.getId(buid, floor_number)
      try {
        val stored_floor = ProxyDataSource.getIDatasource.getFromKeyAsJson(fuid)
        if (stored_floor == null) AnyResponseHelper.bad_request("Floor does not exist or could not be retrieved!")
        stored_floor.put("bottom_left_lat", bottom_left_lat)
        stored_floor.put("bottom_left_lng", bottom_left_lng)
        stored_floor.put("top_right_lat", top_right_lat)
        stored_floor.put("top_right_lng", top_right_lng)
        if (!ProxyDataSource.getIDatasource.replaceJsonDocument(fuid, 0, stored_floor.toString)) AnyResponseHelper.bad_request("Floor plan could not be updated in the database!")
      } catch {
        case e: DatasourceException => AnyResponseHelper.internal_server_error("Error while reading from our backend service!")
      }
      var floor_file: File = null
      try {
        floor_file = AnyPlaceTilerHelper.storeFloorPlanToServer(buid, floor_number, floorplan.ref.file)
      } catch {
        case e: AnyPlaceException => AnyResponseHelper.bad_request("Cannot save floor plan on the server!")
      }
      val top_left_lat = top_right_lat
      val top_left_lng = bottom_left_lng
      try {
        AnyPlaceTilerHelper.tileImage(floor_file, top_left_lat, top_left_lng)
      } catch {
        case e: AnyPlaceException => AnyResponseHelper.bad_request("Could not create floor plan tiles on the server!")
      }
      LPLogger.info("Successfully tiled [" + floor_file.toString + "]")
      AnyResponseHelper.ok("Successfully updated floor plan!")
  }

      def addAccount() = Action {
    implicit request =>

      val anyReq = new OAuth2Request(request)
      if (!anyReq.assertJsonBody()) AnyResponseHelper.bad_request(AnyResponseHelper.CANNOT_PARSE_BODY_AS_JSON)
      var json = anyReq.getJsonBody
      LPLogger.info("AnyplaceAccounts::addAccount():: ")
      val notFound = JsonUtils.requirePropertiesInJson(json, "access_token", "type")
      if (!notFound.isEmpty) AnyResponseHelper.requiredFieldsMissing(notFound)
      if (json.\("access_token").get == null) AnyResponseHelper.forbidden("Unauthorized")
      var owner_id = verifyOwnerId((json \ "access_token").as[String])
      if (owner_id == null) AnyResponseHelper.forbidden("Unauthorized")
      owner_id = appendToOwnerId(owner_id)
      json = json.as[JsObject] + ("owner_id" -> Json.toJson(owner_id))

      val newAccount = new Account(JsonObject.fromJson(json.toString()))
      try {
        if (!ProxyDataSource.getIDatasource.addJsonDocument(newAccount.getId, 0, newAccount.toValidCouchJson().toString)) AnyResponseHelper.ok("Returning user.")
        val res = JsonObject.empty()
        AnyResponseHelper.ok("New user.")
      } catch {
        case e: DatasourceException => AnyResponseHelper.internal_server_error("Server Internal Error [" + e.getMessage + "]")
      }
  }

  private def isBuildingOwner(building: JsonObject, userId: String): Boolean = {
    if (building != null && building.get("owner_id") != null &&
      building.getString("owner_id") == userId) true
    false
  }

  private def isBuildingCoOwner(building: JsonObject, userId: String): Boolean = {
    val cws: JsonArray = building.getArray("co_owners")
    if (building != null && !cws.isEmpty) {
      val it = cws.iterator()
      while (it.hasNext) if (it.next().toString == userId) true
    }
    false
  }


  private def gzippedJSONOk(body: String) = {
    val gzipv = gzip(body)
    Ok(gzipv.toByteArray).withHeaders(("Content-Encoding", "gzip"),
      ("Content-Length", gzipv.size + ""),
      ("Content-Type", "application/json"))
  }

  private def gzippedOk(body: String) = {
    val gzipv = gzip(body)
    Ok(gzipv.toByteArray).withHeaders(("Content-Encoding", "gzip"), ("Content-Length", gzipv.size + ""))
  }

  private def gzip(input: String) = {
    val inputStream = new ByteArrayInputStream(input.getBytes)
    val stringOutputStream = new ByteArrayOutputStream((input.length * 0.75).toInt)
    val gzipOutputStream = new GZIPOutputStream(stringOutputStream)
    val buf = Array.ofDim[Byte](5000)
    var len = 0
    len = inputStream.read(buf)
    while (len > 0) {
      gzipOutputStream.write(buf, 0, len)
      len = inputStream.read(buf)
    }
    inputStream.close()
    gzipOutputStream.close()
    stringOutputStream
  }
}
