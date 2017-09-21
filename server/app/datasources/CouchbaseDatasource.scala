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
package datasources

import db_models.Connection
import db_models.Poi
import db_models.RadioMapRaw
import floor_module.IAlgo
import play.Logger
import play.Play
import utils.GeoPoint
import utils.JsonUtils
import utils.LPLogger
import java.io.FileOutputStream
import java.io.IOException
import java.io.PrintWriter
import java.net.URI
import java.util
import java.util._
import java.util.concurrent.TimeUnit

import accounts.IAccountService
import com.couchbase.client.java.document.JsonDocument
import com.couchbase.client.java.document.json.{JsonArray, JsonObject}
import com.couchbase.client.java.env.DefaultCouchbaseEnvironment
import com.couchbase.client.java.view.{SpatialView, SpatialViewQuery, ViewQuery}
import com.couchbase.client.java.{Bucket, CouchbaseCluster, PersistTo}
import oauth.provider.v2.models.{AccessTokenModel, AccountModel, AuthInfo}
import oauth.provider.v2.token.TokenService
//remove if not needed
import scala.collection.JavaConversions._

object CouchbaseDatasource {

  private var sInstance: CouchbaseDatasource = null
  private var sLockInstance: AnyRef = new AnyRef()

  def getStaticInstance: CouchbaseDatasource = {
    sLockInstance.synchronized {
      if (sInstance == null) {
        val hostname = Play.application().configuration().getString("couchbase.hostname")
        val port = Play.application().configuration().getString("couchbase.port")
        val username = Play.application().configuration().getString("couchbase.username")
        val bucket = Play.application().configuration().getString("couchbase.bucket")
        val password = Play.application().configuration().getString("couchbase.password")
        sInstance = CouchbaseDatasource.createNewInstance(hostname, port, bucket, username, password)
        try {
          sInstance.init()
        } catch {
          case e: DatasourceException => LPLogger.error("CouchbaseDatasource::getStaticInstance():: Exception while instantiating Couchbase [" +
            e.getMessage +
            "]")
        }
      }
      sInstance
    }
  }

  def createNewInstance(hostname_in: String,
                        port_in: String,
                        bucket_in: String,
                        username_in: String,
                        password_in: String): CouchbaseDatasource = {
    if (hostname_in == null || port_in == null || bucket_in == null || password_in == null) {
      throw new IllegalArgumentException("[null] parameters are not allowed to create a CouchbaseDatasource")
    }
    val hostname = hostname_in.trim()
    val port = port_in.trim()
    val bucket = bucket_in.trim()
    val password = password_in.trim()
    val username = username_in.trim()
    if (hostname.isEmpty || port.isEmpty || bucket.isEmpty || username.isEmpty || password.isEmpty) {
      throw new IllegalArgumentException("Empty string configuration are not allowed to create a CouchbaseDatasource")
    }
    new CouchbaseDatasource(hostname, port, bucket, username, password)
  }
}

class CouchbaseDatasource private(hostname: String,
                                  port: String,
                                  bucket: String,
                                  username: String,
                                  password: String) extends IDatasource with IAccountService {

  private var mHostname: String = hostname

  private var mPort: String = port

  private var mBucket: String = bucket


  private var mUsername: String = username

  private var mPassword: String = password

  private var mCluster: CouchbaseCluster = _

  private var mSecureBucket: Bucket = _

  private def connect(): Boolean = {
    Logger.info("Trying to connect to: " + mHostname + ":" + mPort + " bucket[" +
      mBucket +
      "] password: " +
      mPassword)
    val uris = new LinkedList[URI]()
    uris.add(URI.create(mHostname + ":" + mPort + "/pools"))
    try {
      val env = DefaultCouchbaseEnvironment
        .builder()
        .autoreleaseAfter(100000) //100000ms = 100s, default is 2s
        .connectTimeout(100000) //100000ms = 100s, default is 5s
        .socketConnectTimeout(100000) //100000ms = 100s, default is 5s
        .build()

      // Connects to a cluster on hostname
      // if the other one does not respond during bootstrap.
      mCluster = CouchbaseCluster.create(env, mHostname)

      mSecureBucket = mCluster.openBucket(mBucket, mPassword)
    } catch {
      case e: java.net.SocketTimeoutException =>
        LPLogger.error("CouchbaseDatasource::connect():: Error connection to Couchbase: " +
          e.getMessage)
        throw new DatasourceException("Cannot connect to Anyplace Database [SocketTimeout]!")
      case e: IOException =>
        LPLogger.error("CouchbaseDatasource::connect():: Error connection to Couchbase: " +
          e.getMessage)
        throw new DatasourceException("Cannot connect to Anyplace Database [IO]!")
      case e: Exception =>
        LPLogger.error("CouchbaseDatasource::connect():: Error connection to Couchbase: " +
          e.getMessage)
        throw new DatasourceException("Cannot connect to Anyplace Database! [Unknown]")
    }
    true
  }

  def disconnect(): Boolean = {
    // Just close a single bucket
    var res = mSecureBucket.close()

    // Disconnect and close all buckets
    res = res & mCluster.disconnect()
    res
  }

  def getConnection: Bucket = {

    if (mSecureBucket == null) {
      connect()
    }
    mSecureBucket
  }

  override def init(): Boolean = {
    try {
      this.connect()
    } catch {
      case e: DatasourceException => {
        LPLogger.error("CouchbaseDatasource::init():: " + e.getMessage)
        throw new DatasourceException("Cannot establish connection to Anyplace database!")
      }
    }
    true
  }

  override def addJsonDocument(key: String, expiry: Int, document: String): Boolean = {
    val client = getConnection.async()
    val content = JsonObject.fromJson(document)
    val json = JsonDocument.create(key, content)
    val db_res = client.upsert(json, PersistTo.ONE).toBlocking.first()
    db_res.equals(json)
  }

  override def replaceJsonDocument(key: String, expiry: Int, document: String): Boolean = {
    val client = getConnection
    val content = JsonObject.fromJson(document)
    val json = JsonDocument.create(key, content)
    val db_res = client.replace(json, PersistTo.ONE, expiry, TimeUnit.MILLISECONDS)
    db_res.equals(json)
  }

  override def deleteFromKey(key: String): Boolean = {
    val client = getConnection
    val db_res = client.remove(key, PersistTo.ONE)
    true
  }

  override def getFromKey(key: String): JsonDocument = {
    val client = getConnection
    val db_res = client.get(key)
    db_res
  }

  override def getFromKeyAsJson(key: String): JsonObject = {
    if (key == null || key.trim().isEmpty) {
      throw new IllegalArgumentException("No null or empty string allowed as key!")
    }
    val db_res = getFromKey(key)
    if (db_res == null) {
      return null
    }
    try {
      val jsonNode = db_res.content()
      jsonNode
    } catch {
      case e: IOException => {
        LPLogger.error("CouchbaseDatasource::getFromKeyAsJson():: Could not convert document from Couchbase into JSON!")
        null
      }
    }
  }

  override def buildingFromKeyAsJson(key: String): JsonObject = {
    val building = getFromKeyAsJson(key)
    if (building == null) {
      return null
    }
    val floors = JsonArray.empty()
    for (f <- floorsByBuildingAsJson(key)) {
      floors.add(f)
    }
    building.put("floors", floors)
    val pois = JsonArray.empty()
    for (p <- poisByBuildingAsJson(key)) {
      if (p.getString("pois_type") == Poi.POIS_TYPE_NONE) //continue
        pois.add(p)
    }
    building.put("pois", floors)
    building
  }

  override def poiFromKeyAsJson(key: String): JsonObject = getFromKeyAsJson(key)

  override def poisByBuildingFloorAsJson(buid: String, floor_number: String): List[JsonObject] = {
    val couchbaseClient = getConnection
    val viewQuery = ViewQuery.from("nav", "pois_by_buid_floor").key(JsonArray.from(buid, floor_number))

    val res = couchbaseClient.query(viewQuery)
    if (0 == res.totalRows()) {
      return Collections.emptyList()
    }
    val result = new ArrayList[JsonObject]()
    var json: JsonObject = null
    if (res.totalRows() > 0)
      for (row <- res.allRows()) {
        try {
          json = row.document().content()
          json.removeKey("owner_id")
          json.removeKey("geometry")
          result.add(json)
        } catch {
          case e: IOException =>
        }
      }
    result
  }

  override def poisByBuildingFloorAsMap(buid: String, floor_number: String): List[HashMap[String, String]] = {
    val couchbaseClient = getConnection
    val viewQuery = ViewQuery.from("nav", "pois_by_buid_floor").key(JsonArray.from(buid, floor_number))

    val res = couchbaseClient.query(viewQuery)
    if (0 == res.size) {
      return Collections.emptyList()
    }
    val result = new ArrayList[HashMap[String, String]]()
    if (res.totalRows() > 0)
      for (row <- res) {
        result.add(JsonUtils.getHashMapStrStr(row.document().toString))
      }
    result
  }

  override def poisByBuildingAsJson(buid: String): List[JsonObject] = {
    val couchbaseClient = getConnection
    val viewQuery = ViewQuery.from("nav", "pois_by_buid").key((buid))

    val res = couchbaseClient.query(viewQuery)
    val pois = new ArrayList[JsonObject]()
    var json: JsonObject = null
    if (res.totalRows() > 0)
      for (row <- res) {
        try {
          json = row.document().content()
          json.removeKey("owner_id")
          json.removeKey("geometry")
          pois.add(json)
        } catch {
          case e: IOException =>
        }
      }
    pois
  }

  override def poisByBuildingAsMap(buid: String): List[HashMap[String, String]] = {
    val couchbaseClient = getConnection
    val viewQuery = ViewQuery.from("nav", "pois_by_buid").key((buid))

    val res = couchbaseClient.query(viewQuery)

    val pois = new ArrayList[HashMap[String, String]]()
    if (res.totalRows() > 0)
      for (row <- res) {
        pois.add(JsonUtils.getHashMapStrStr(row.document().toString))
      }
    pois
  }

  override def floorsByBuildingAsJson(buid: String): List[JsonObject] = {
    val floors = new ArrayList[JsonObject]()
    val couchbaseClient = getConnection
    val viewQuery = ViewQuery.from("nav", "floor_by_buid").key(buid).includeDocs(true)

    val res = couchbaseClient.query(viewQuery)
    println("couchbase results: " + res.totalRows())
    if (!res.success()) {
      throw new DatasourceException("Error retrieving floors from database!")
    }
    var json: JsonObject = null
    if (res.totalRows() > 0)
      for (row <- res.allRows()) {
        try {
          json = row.document().content()
          json.removeKey("owner_id")
          floors.add(json)
        } catch {
          case e: IOException =>
        }
      }
    floors
  }

  @throws[DatasourceException] override def connectionsByBuildingAllFloorsAsJson(buid: String): util.List[JsonObject] = {
    val result = new ArrayList[JsonObject]()
    val couchbaseClient = getConnection
    val viewQuery = ViewQuery.from("nav", "connection_by_buid_all_floors").key((buid))

    val res = couchbaseClient.query(viewQuery)
    println("couchbase results: " + res.size)
    if (!res.success()) {
      throw new DatasourceException("Error retrieving floors from database!")
    }
    var json: JsonObject = null
    if (res.totalRows() > 0)
      for (row <- res.allRows()) {
        try {
          json = row.document().content()
          json.removeKey("owner_id")
          result.add(json)
        } catch {
          case e: IOException =>
        }
      }
    result
  }

  override def connectionsByBuildingAsJson(buid: String): List[JsonObject] = {

    val couchbaseClient = getConnection
    val viewQuery = ViewQuery.from("nav", "connection_by_buid").key((buid))

    val res = couchbaseClient.query(viewQuery)
    var json: JsonObject = null
    val conns = new ArrayList[JsonObject]()
    if (res.totalRows() > 0)
      for (row <- res) {
        try {
          json = row.document().content()
          if (json.getString("edge_type").equalsIgnoreCase(Connection.EDGE_TYPE_OUTDOOR)) //continue
            conns.add(json)
        } catch {
          case e: IOException =>
        }
      }
    conns
  }


  override def connectionsByBuildingAsMap(buid: String): List[HashMap[String, String]] = {
    val couchbaseClient = getConnection
    val viewQuery = ViewQuery.from("nav", "connection_by_buid").key((buid))

    val res = couchbaseClient.query(viewQuery)
    var hm: HashMap[String, String] = null
    val conns = new ArrayList[HashMap[String, String]]()
    if (res.totalRows() > 0)
      for (row <- res) {
        hm = JsonUtils.getHashMapStrStr(row.document().toString)
        if (hm.get("edge_type").equalsIgnoreCase(Connection.EDGE_TYPE_OUTDOOR)) //continue
          conns.add(hm)
      }
    conns
  }

  override def connectionsByBuildingFloorAsJson(buid: String, floor_number: String): List[JsonObject] = {
    val couchbaseClient = getConnection
    val viewQuery = ViewQuery.from("nav", "connection_by_buid_floor").key(JsonArray.from(buid, floor_number))

    val res = couchbaseClient.query(viewQuery)
    if (0 == res.size) {
      return Collections.emptyList()
    }
    val result = new ArrayList[JsonObject]()
    var json: JsonObject = null
    if (res.totalRows() > 0)
      for (row <- res) {
        try {
          json = row.document().content()
          json.removeKey("owner_id")
          result.add(json)
        } catch {
          case e: IOException =>
        }
      }
    result
  }

  override def deleteAllByBuilding(buid: String): List[String] = {
    val all_items_failed = new ArrayList[String]()
    val couchbaseClient = getConnection
    val viewQuery = ViewQuery.from("nav", "all_by_buid").key((buid))

    val res = couchbaseClient.query(viewQuery)

    if (res.totalRows() > 0)
      for (row <- res) {
        val id = row.id()
        val db_res = couchbaseClient.remove(id, PersistTo.ONE)
        try {
          if (db_res.id.ne(id)) {
            all_items_failed.add(id)
          } else {
          }
        } catch {
          case e: Exception => all_items_failed.add(id)
        }
      }
    all_items_failed
  }

  override def deleteAllByFloor(buid: String, floor_number: String): List[String] = {
    val all_items_failed = new ArrayList[String]()
    val couchbaseClient = getConnection
    val viewQuery = ViewQuery.from("nav", "all_by_floor").key((buid))
    val res = couchbaseClient.query(viewQuery)
    if (res.totalRows() > 0)
      for (row <- res) {
        val id = row.id()
        val db_res = couchbaseClient.remove(id, PersistTo.ONE)
        try {
          if (db_res.id.ne(id)) {
            all_items_failed.add(id)
          } else {
          }
        } catch {
          case e: Exception => all_items_failed.add(id)
        }
      }
    all_items_failed
  }

  override def deleteAllByConnection(cuid: String): List[String] = {
    val all_items_failed = new ArrayList[String]()
    if (!this.deleteFromKey(cuid)) {
      all_items_failed.add(cuid)
    }
    all_items_failed
  }

  override def deleteAllByPoi(puid: String): List[String] = {
    val all_items_failed = new ArrayList[String]()
    val couchbaseClient = getConnection
    val viewQuery = ViewQuery.from("nav", "all_by_pois").key((puid))
    val res = couchbaseClient.query(viewQuery)

    if (res.totalRows() > 0)
      for (row <- res) {
        val id = row.id()
        val db_res = couchbaseClient.remove(id, PersistTo.ONE)
        try {
          if (db_res.id.ne(id)) {
            all_items_failed.add(id)
          } else {
          }
        } catch {
          case e: Exception => all_items_failed.add(id)
        }
      }
    all_items_failed
  }

  override def getRadioHeatmap(): List[JsonObject] = {
    val points = new ArrayList[JsonObject]()
    val couchbaseClient = getConnection
    val viewQuery = ViewQuery.from("radio", "radio_new_campus_experiment").group(true).reduce(true)
    val res = couchbaseClient.query(viewQuery)

    println("couchbase results: " + res.size)
    var json: JsonObject = null
    if (res.totalRows() > 0)
      for (row <- res) {
        try {
          json = row.key().asInstanceOf[JsonObject]
          json.put("weight", row.value().toString)
          points.add(json)
        } catch {
          case e: IOException =>
        }
      }
    points
  }

  override def getRadioHeatmapByBuildingFloor(buid: String, floor: String): List[JsonObject] = {
    val points = new ArrayList[JsonObject]()
    val couchbaseClient = getConnection
    val viewQuery = ViewQuery.from("radio", "radio_heatmap_building_floor").key(JsonArray.from(buid, floor)).group(true).reduce(true)
    val res = couchbaseClient.query(viewQuery)

    println("couchbase results: " + res.size)
    var json: JsonObject = null
    if (res.totalRows() > 0)
      for (row <- res.allRows()) {
        try {
          json = JsonObject.empty()
          var k = row.key().toString
          val array = k.split(",")
          json.put("x", array(2))
          json.put("y", array(3))
          json.put("w", row.value().toString)
          points.add(json)
        } catch {
          case e: IOException =>
        }
      }
    points
  }

  override def getAllBuildings(): List[JsonObject] = {

    val buildings = new ArrayList[JsonObject]()
    val couchbaseClient = getConnection
    val viewQuery = ViewQuery.from("nav", "building_all").includeDocs(true)
    val res = couchbaseClient.query(viewQuery)
    //  if (res.totalRows() > 0)

    for (row <- res.allRows()) {
      try {
        val json = row.document().content()
        json.removeKey("geometry")
        json.removeKey("owner_id")
        json.removeKey("co_owners")
        buildings.add(json)

      } catch {
        case e: IOException =>
      }
    }

    val test = JsonObject.empty().put("name", " 星网:")
    val name = " 星网:"
    println(test.toString)
    println(name)
    buildings
  }

  override def getAllBuildingsByOwner(oid: String): List[JsonObject] = {
    val buildings = new ArrayList[JsonObject]()
    val couchbaseClient = getConnection
    val viewQuery = ViewQuery.from("nav", "building_all_by_owner").key((oid)).includeDocs(true)
    val res = couchbaseClient.query(viewQuery)

    println("couchbase results: " + res.totalRows)
    var json: JsonObject = null
    if (res.totalRows() > 0)
      for (row <- res.allRows()) {
        try {
          json = row.document().content()
          json.removeKey("geometry")
          json.removeKey("owner_id")
          json.removeKey("co_owners")
          buildings.add(json)
        } catch {
          case e: Exception =>
        }
      }
    buildings
  }

  override def getAllBuildingsByBucode(bucode: String): List[JsonObject] = {
    val buildings = new ArrayList[JsonObject]()
    val couchbaseClient = getConnection
    val viewQuery = ViewQuery.from("nav", "building_all_by_bucode").key((bucode)).includeDocs(true)
    val res = couchbaseClient.query(viewQuery)
    var json: JsonObject = null
    if (res.totalRows() > 0)
      for (row <- res) {
        try {
          json = row.document().content()
          json.removeKey("geometry")
          json.removeKey("owner_id")
          json.removeKey("co_owners")
          buildings.add(json)
        } catch {
          case e: Exception =>
        }
      }
    buildings
  }

  override def getAllBuildingsNearMe(lat: Double, lng: Double): List[JsonObject] = {
    val buildings = new ArrayList[JsonObject]()
    val couchbaseClient = getConnection

    val bbox = GeoPoint.getGeoBoundingBox(lat, lng, 50)
    val viewQuery = SpatialViewQuery.from("nav_spatial", "building_coordinates")
      .startRange(JsonArray.from(new java.lang.Double(bbox(0).dlat), new java.lang.Double(bbox(0).dlon)))
      .endRange(JsonArray.from(new java.lang.Double(bbox(1).dlat), new java.lang.Double(bbox(1).dlon))).includeDocs(true)
    val res = couchbaseClient.query(viewQuery)

    println("couchbase results: " + res.size)
    var json: JsonObject = null
    if (res.nonEmpty)
      for (row <- res) {
        try {
          json = row.document().content()
          json.removeKey("geometry")
          json.removeKey("owner_id")
          json.removeKey("co_owners")
          buildings.add(json)
        } catch {
          case e: IOException =>
        }
      }
    buildings
  }

  var allPoisSide = new util.HashMap[String, util.List[JsonObject]]()

  var allPoisbycuid = new util.HashMap[String, util.List[JsonObject]]()

  override def getBuildingByAlias(alias: String): JsonObject = {
    var jsn: JsonObject = null
    val couchbaseClient = getConnection
    val viewQuery = ViewQuery.from("nav", "building_by_alias").key((alias)).includeDocs(true)
    val res = couchbaseClient.query(viewQuery)
    // println("couchbase results: " + res.size)
    if (!res.iterator().hasNext) {
      return null
    }
    try {
      jsn = res.iterator().next().document().content()
    } catch {
      case ioe: IOException =>
    }
    jsn
  }


  @throws[DatasourceException]
  override def getBuildingSet(cuid2: String): List[JsonObject] = {
    val buildingSet = new ArrayList[JsonObject]()
    val allPois = new ArrayList[JsonObject]()
    val couchbaseClient = getConnection
    val viewQuery = ViewQuery.from("nav", "get_campus").includeDocs(true)
    val res = couchbaseClient.query(viewQuery)
    var json: JsonObject = null
    var break = false
    for (row <- res.allRows() if !break) { // handle each building entry
      try {
        json = row.document().content()
        val cuid = json.toString
        json.removeKey("owner_id")
        json.removeKey("description")
        if (cuid.contains(cuid2)) {
          json.removeKey("cuid")
          buildingSet.add(json)
          break = true
        }
      } catch {
        case e: IOException =>

        // skip this NOT-JSON document
      }
    }
    //allPoisSide.put(cuid2,);
    if (allPoisbycuid.get(cuid2) == null) {
      System.out.println("LOAD CUID:" + cuid2)
      var i = 0
      for ( i <- 0 until buildingSet.get(0).getArray("buids").size )
   {
        val buid = buildingSet.get(0).getArray("buids").get(i).toString
        if (allPoisSide.get(buid) != null) {
          val pois = allPoisSide.get(buid)
          allPois.addAll(pois)
        }
        else {
          val pois = poisByBuildingAsJson(buid)
          allPoisSide.put(buid, pois)
          allPois.addAll(pois)
        }
      }
      allPoisbycuid.put(cuid2, allPois)
    }
    buildingSet
  }

  @throws[DatasourceException] override def BuildingSetsCuids(cuid2: String): Boolean = {
    val couchbaseClient = getConnection
    val viewQuery = ViewQuery.from("nav", "get_campus").includeDocs(true)
    val res = couchbaseClient.query(viewQuery)
    System.out.println("couchbase results: " + res.size)
    var json: JsonObject = null
    if (res.nonEmpty)
      for (row <- res) {
        try {
          json = row.document().content()
          var cuid = json.getString("cuid")
          if (cuid.compareTo(cuid2) == 0) return true
        } catch {
          case e: IOException =>
          // skip this NOT-JSON document
        }
      }
    false
  }


  @throws[DatasourceException]
  override def getAllBuildingsetsByOwner(oid: String): util.List[JsonObject] = {
    val buildingsets = new util.ArrayList[JsonObject]
    val couchbaseClient = getConnection
    val viewQuery = ViewQuery.from("nav", "cuid_all_by_owner").key((oid)).includeDocs(true)
    val res = couchbaseClient.query(viewQuery)

    System.out.println("couchbase results campus: " + res.totalRows())

    var json: JsonObject = null
    for (row <- res.allRows()) {
      try {
        json = row.document().content()
        json.removeKey("owner_id")
        buildingsets.add(json)
      } catch {
        case e: Exception =>

        // skip this NOT-JSON document
      }
    }
    buildingsets
  }

  override def dumpRssLogEntriesSpatial(outFile: FileOutputStream, bbox: Array[GeoPoint], floor_number: String): Long = {
    val writer = new PrintWriter(outFile)
    var view = null
    val queryLimit = 5000
    var totalFetched = 0
    var currentFetched = 0
    var floorFetched = 0
    var rssEntry: JsonObject = null
    val couchbaseClient = getConnection

    do {
      val viewQuery = SpatialViewQuery.from("nav_spatial", "building_coordinates")
        .startRange(JsonArray.from(new java.lang.Double(bbox(0).dlat), new java.lang.Double(bbox(0).dlon)))
        .endRange(JsonArray.from(new java.lang.Double(bbox(1).dlat), new java.lang.Double(bbox(1).dlon))).includeDocs(true).limit(queryLimit).skip(totalFetched)
      val res = couchbaseClient.query(viewQuery)
      currentFetched = 0
      for (row <- res) {
        currentFetched += 1
        try {
          rssEntry = row.document().content()
        } catch {
          case e: IOException => //continue
        }
        if (rssEntry.getString("floor") != floor_number) {
          //continue
        }
        floorFetched += 1
        writer.println(RadioMapRaw.toRawRadioMapRecord(rssEntry))
      }
      totalFetched += currentFetched
      LPLogger.info("total fetched: " + totalFetched)
    } while (currentFetched >= queryLimit && floorFetched < 100000);
    writer.flush()
    writer.close()
    floorFetched
  }

  override def dumpRssLogEntriesByBuildingFloor(outFile: FileOutputStream, buid: String, floor_number: String): Long = {
    val writer = new PrintWriter(outFile)
    val couchbaseClient = getConnection
    val queryLimit = 10000
    var totalFetched = 0
    var currentFetched: Int = 0
    var rssEntry: JsonObject = null

    var viewQuery = ViewQuery.from("radio", "raw_radio_building_floor").key(JsonArray.from(buid, floor_number)).includeDocs(true)

    do {
      viewQuery = ViewQuery.from("radio", "raw_radio_building_floor").key(JsonArray.from(buid, floor_number)).includeDocs(true).limit(queryLimit).skip(totalFetched)

      val res = couchbaseClient.query(viewQuery)
      if (!(res.totalRows() > 0)) return totalFetched
      currentFetched = 0
      if (res.totalRows() > 0)
        for (row <- res) {
          currentFetched += 1
          try {
            rssEntry = row.document().content()
          } catch {
            case e: IOException => //continue
          }
          writer.println(RadioMapRaw.toRawRadioMapRecord(rssEntry))
        }
      totalFetched += currentFetched
      LPLogger.info("total fetched: " + totalFetched)
    } while (currentFetched >= queryLimit);
    writer.flush()
    writer.close()
    totalFetched
  }

  override def getAllAccounts(): List[JsonObject] = {
    val accounts = new ArrayList[JsonObject]()

    val couchbaseClient = getConnection
    val viewQuery = ViewQuery.from("accounts", "accounts_all").includeDocs(true)

    val res = couchbaseClient.query(viewQuery)

    println("couchbase results: " + res.size)
    if (res.error().size > 0) {
      throw new DatasourceException("Error retrieving accounts from database!")
    }
    var json: JsonObject = null
    if (res.totalRows() > 0)
      for (row <- res) {
        try {
          if (row.document() == null) //continue
            json = row.document().content()
          json.removeKey("doctype")
          accounts.add(json)
        } catch {
          case e: IOException =>
        }
      }
    accounts
  }

  override def deleteRadiosInBox(): Boolean = {
    val couchbaseClient = getConnection
    val viewQuery = ViewQuery.from("radio", "tempview").includeDocs(true)
    val res = couchbaseClient.query(viewQuery)
    if (res.totalRows() > 0)
      for (row <- res) {
        deleteFromKey(row.key().toString)
      }
    true
  }

  override def predictFloor(algo: IAlgo, bbox: Array[GeoPoint], strongestMAC: Array[String]): Boolean = {
    predictFloorFast(algo, bbox, strongestMAC)
  }

  private def predictFloorFast(algo: IAlgo, bbox: Array[GeoPoint], strongestMACs: Array[String]): Boolean = {
    val designDoc = "floor"
    val viewName = "group_wifi"
    val couchbaseClient = getConnection
    var totalFetched = 0
    for (strongestMAC <- strongestMACs) {

      /**
        * val startkey: ComplexKey = ComplexKey.of(strongestMAC, new java.lang.Double(bbox(0).dlat), new java.lang.Double(bbox(0).dlon), null)
        * val endkey: ComplexKey = ComplexKey.of(strongestMAC, new java.lang.Double(bbox(1).dlat), new java.lang.Double(bbox(1).dlon), "࿿")
        *query.setRange(startkey, endkey)
        */

      val viewQuery = SpatialViewQuery.from(designDoc, viewName)
        .startRange(JsonArray.from(new java.lang.Double(bbox(0).dlat), new java.lang.Double(bbox(0).dlon)))
        .endRange(JsonArray.from(new java.lang.Double(bbox(1).dlat), new java.lang.Double(bbox(1).dlon))).includeDocs(true).skip(totalFetched)
      val response = couchbaseClient.query(viewQuery)


      var _timestamp = ""
      var _floor = "0"
      val bucket = new ArrayList[JsonObject](10)
      if (response.nonEmpty)
        for (row <- response) {
          try {
            val timestamp = row.key.toString
            if (timestamp == strongestMAC || timestamp == "࿿") {
              val value = row.value().asInstanceOf[JsonObject]
              if (_timestamp != timestamp) {
                if (_timestamp != "") {
                  algo.proccess(bucket, _floor)
                }
                bucket.clear()
                _timestamp = timestamp
                _floor = value.getString("floor")
              }
              bucket.add(value)
              totalFetched += 1
            }
          } catch {
            case e: IOException => //continue
          }
        }
    }
    LPLogger.info("total fetched: " + totalFetched)
    if (totalFetched > 10) {
      true
    } else {
      false
    }
  }

  override def magneticPathsByBuildingFloorAsJson(buid: String, floor_number: String): List[JsonObject] = {
    val couchbaseClient = getConnection
    val viewQuery = ViewQuery.from("magnetic", "mpaths_by_buid_floor").key(JsonArray.from(buid, floor_number)).includeDocs(true)
    val res = couchbaseClient.query(viewQuery)

    if (0 == res.size) {
      return Collections.emptyList()
    }
    val result = new ArrayList[JsonObject]()
    var json: JsonObject = null
    if (res.totalRows() > 0)
      for (row <- res) {
        try {
          json = row.document().content()
          result.add(json)
        } catch {
          case e: IOException =>
        }
      }
    result
  }

  override def magneticPathsByBuildingAsJson(buid: String): List[JsonObject] = {
    val couchbaseClient = getConnection
    val viewQuery = ViewQuery.from("magnetic", "mpaths_by_buid").key((buid)).includeDocs(true)

    val res = couchbaseClient.query(viewQuery)
    if (0 == res.size) {
      return Collections.emptyList()
    }
    val result = new ArrayList[JsonObject]()
    var json: JsonObject = null
    if (res.totalRows() > 0)
      for (row <- res) {
        try {
          json = row.document().content()
          result.add(json)
        } catch {
          case e: IOException =>
        }
      }
    result
  }

  override def magneticMilestonesByBuildingFloorAsJson(buid: String, floor_number: String): List[JsonObject] = {
    val couchbaseClient = getConnection
    val viewQuery = ViewQuery.from("magnetic", "mmilestones_by_buid_floor").key(JsonArray.from(buid, floor_number)).includeDocs(true)
    val res = couchbaseClient.query(viewQuery)
    if (0 == res.size) {
      return Collections.emptyList()
    }
    val result = new ArrayList[JsonObject]()
    var json: JsonObject = null
    if (res.totalRows() > 0)
      for (row <- res) {
        try {
          json = row.document().content()
          result.add(json)
        } catch {
          case e: IOException =>
        }
      }
    result
  }

  override def validateClient(clientId: String,
                              clientSecret: String,
                              grantType: String): AccountModel = {
    var couchbaseClient: Bucket = null

    try couchbaseClient = getConnection
    catch {
      case e: DatasourceException => {
        LPLogger.error(
          "CouchbaseDatasource::validateClient():: Exception while getting connection in DB")
        null
      }

    }

    val viewQuery = ViewQuery.from("accounts", "accounts_by_client_id").key((clientId)).includeDocs(true)

    val res = couchbaseClient.query(viewQuery)

    var accountJson: JsonObject = null
    var clients: JsonArray = null
    var found: Boolean = false
    for (row <- res) {
      try accountJson = row.document().content()
      catch {
        case e: IOException => null

      }
      clients = accountJson.getArray("clients")

      found = clients.exists(client =>
        client.asInstanceOf[JsonObject].getString("client_id") == clientId &&
          client.asInstanceOf[JsonObject].getString("client_secret") == clientSecret &&
          client.asInstanceOf[JsonObject].getString("grant_type") == grantType)

    }
    if (found) {

      val account: AccountModel = new AccountModel(accountJson)
      account
    }

    null
  }

  override def validateAccount(account: AccountModel,
                               username: String,
                               password: String): Boolean = {
    if (account == null) false
    if (username == null || username.trim().isEmpty) {
      false
    }
    // TODO- or even maybe salting them
    if (username != account.getUsername) {
      false
    }
    if (password == null || password.trim().isEmpty) {
      false
    }
    // TODO- or even maybe salting them
    if (password != account.getPassword) {
      false
    }
    true
  }

  // TODO- here maybe we should add Base64 decoding for the credentials
  // TODO- here maybe we should add Base64 decoding for the credentials
  // TODO- here maybe we should add Base64 decoding for the credentials
  // TODO- here maybe we should add Base64 decoding for the credentials

  override def createOrUpdateAuthInfo(account: AccountModel,
                                      clientId: String,
                                      scope: String): AuthInfo = {
    // validate the scopes
    if (!account.validateScope(scope, clientId)) {
      null
    }
    // TODO - IMPORTANT - no refresh token is issued at the moment
    new AuthInfo(account.getAuid, clientId, scope)
  }

  // TODO - maybe the database here first in order to check
  // TODO - if there is already a refresh token available
  // TODO - maybe the database here first in order to check
  // TODO - if there is already a refresh token available

  override def createOrUpdateAccessToken(authInfo: AuthInfo): AccessTokenModel = {
    var client: Bucket = null
    val act: AccessTokenModel = null
    try client = getConnection
    catch {
      case e: DatasourceException => {
        LPLogger.error(
          "CouchbaseDatasource::createOrUpdateAccessToken():: Exception while opening connection to store new token in DB")
        return act
      }
    }

    while (true) {
      val tokenModel: AccessTokenModel =
        TokenService.createNewAccessToken(authInfo)
      val db_res = client.mapAdd(
        tokenModel.getTuid, tokenModel.getTuid,
        tokenModel.toJson().toString,
        tokenModel.getExpiresIn().toInt, TimeUnit.MILLISECONDS)

      try if (!db_res) {
        //continue
      } else {
        return tokenModel
      } catch {
        case e: Exception =>
          LPLogger.error(
            "CouchbaseDatasource::createOrUpdateAccessToken():: Exception while storing new token in DB")
          return act

      }
    }
    throw new IllegalStateException("This should never happen")
  }

  override def getAuthInfoByRefreshToken(refreshToken: String): AuthInfo = // TODO - not used yet since we do not issue refresh tokens
    null

  override def getRadioHeatmapByBuildingFloor2(lat: String, lon: String, buid: String, floor: String, range: Int): List[JsonObject] = {
    val points = new ArrayList[JsonObject]()
    val couchbaseClient = getConnection
    val viewQuery = ViewQuery.from("radio", "radio_heatmap_building_floor").key(JsonArray.from(buid, floor)).group(true).reduce(true)
    val res = couchbaseClient.query(viewQuery)

    val bbox = GeoPoint.getGeoBoundingBox(lat.toDouble, lon.toDouble, range) // 50 meters radius


    var json: JsonObject = null
    import scala.collection.JavaConversions._
    for (row <- res) { // handle each building entry
      try {
        json = JsonObject.empty()
        var k = row.key().toString
        val array = k.split(",")
        val x = array(2)
        val y = array(3)
        if (x.toDouble >= bbox(0).dlat && x.toDouble <= bbox(1).dlat && y.toDouble >= bbox(0).dlon && y.toDouble <= bbox(1).dlon) {
          json.put("x", x)
          json.put("y", y)
          json.put("w", row.document().content())
          points.add(json)
        }
      } catch {
        case e: IOException =>

        // skip this NOT-JSON document
      }
    }
    points
  }

  override def getRadioHeatmapBBox(lat: String, lon: String, buid: String, floor: String, range: Int): List[JsonObject] = {

    val points = new util.ArrayList[JsonObject]
    val point = new util.HashMap[List[String], Integer]()
    val xy = new util.ArrayList[List[String]]

    val couchbaseClient = getConnection

    val bbox = GeoPoint.getGeoBoundingBox(lat.toDouble, lon.toDouble, range) // 50 meters radius

    val viewQuery = SpatialViewQuery.from("radio", "radio_heatmap_bbox_byxy")
      .startRange(JsonArray.from(new java.lang.Double(bbox(0).dlat), new java.lang.Double(bbox(0).dlon)))
      .endRange(JsonArray.from(new java.lang.Double(bbox(1).dlat), new java.lang.Double(bbox(1).dlon))).includeDocs(true)
    val res = couchbaseClient.query(viewQuery)


    System.out.println("couchbase results: " + res.size)

    var json: JsonObject = null
    for (row <- res) { // handle each building entry
      try {
        json = row.document().content()
        if ((json.getString("buid").compareTo("\"" + buid + "\"") == 0) && (json.getString("floor").compareTo("\"" + floor + "\"") == 0)) {
          val p = new util.ArrayList[String]
          val x = json.getString("x")
          val y = json.getString("y")
          p.add(x)
          p.add(y)
          if (point.containsKey(p)) point.put(p, point.get(p) + 1)
          else {
            point.put(p, 1)
            xy.add(p)
          }
        }
      } catch {
        case e: IOException =>

        // skip this NOT-JSON document
      }
    }
    while ( {
      !xy.isEmpty
    }) {
      val p = xy.remove(0)
      try {
        json = JsonObject.empty()
        val w = "" + point.get(p)
        json.put("x", p.get(0))
        json.put("y", p.get(1))
        json.put("w", w)
        points.add(json)
      } catch {
        case e: IOException =>

        //skip this NOT-JSON document
      }
    }
    points
  }

  override def getRadioHeatmapBBox2(lat: String, lon: String, buid: String, floor: String, range: Int): List[JsonObject] = {

    val points = new util.ArrayList[JsonObject]
    val point = new HashMap[List[String], Integer]

    val couchbaseClient = getConnection

    val bbox = GeoPoint.getGeoBoundingBox(lat.toDouble, lon.toDouble, range) // 50 meters radius

    val viewQuery = SpatialViewQuery.from("radio", "radio_heatmap_bbox_byxy")
      .startRange(JsonArray.from(new java.lang.Double(bbox(0).dlat), new java.lang.Double(bbox(0).dlon)))
      .endRange(JsonArray.from(new java.lang.Double(bbox(1).dlat), new java.lang.Double(bbox(1).dlon))).includeDocs(true)
    val res = couchbaseClient.query(viewQuery)

    var json: JsonObject = null
    var json2: JsonObject = null
    for (row <- res) { // handle each building entry
      try {
        json = row.document().content()
        if ((json.getString("buid").compareTo("\"" + buid + "\"") == 0) && (json.getString("floor").compareTo("\"" + floor + "\"") == 0)) {
          val p = new util.ArrayList[String]
          val x = json.getString("x")
          val y = json.getString("y")
          p.add(x)
          p.add(y)
          if (!point.containsKey(p)) {
            json2 = JsonObject.empty()
            json2.put("x", p.get(0))
            json2.put("y", p.get(1))
            points.add(json2)
            point.put(p, 1)
          }
        }
      } catch {
        case e: IOException =>

        // skip this NOT-JSON document
      }
    }
    points
  }


  var lastletters = ""
  var wordsELOT = new util.ArrayList[util.ArrayList[String]]

  override def poisByBuildingAsJson2GR(cuid: String, letters: String): util.List[JsonObject] = {
    var pois = allPoisbycuid.get(cuid)
    val pois2 = new util.ArrayList[JsonObject]
    val words = letters.split(" ")
    var flag = false
    var flag2 = false
    var flag3 = false
    if (letters.compareTo(lastletters) != 0) {
      lastletters = letters
      wordsELOT = new util.ArrayList[util.ArrayList[String]]
      var j = 0
      while ( {
        j < words.length
      }) {
        wordsELOT.add(greeklishTogreekList(words(j).toLowerCase))

        {
          j += 1;
          j - 1
        }
      }
    }
    for (json <- pois) {
      flag = true
      flag2 = true
      flag3 = true
      var j = 0
      for (w <- words if !(!flag3 && !flag && !flag2)) {
        if (!(json.get("name").toString.toLowerCase.contains(w.toLowerCase) || json.get("description").toString.toLowerCase.contains(w.toLowerCase))) flag = false
        val greeklish = greeklishTogreek(w.toLowerCase)
        if (!(json.get("name").toString.toLowerCase.contains(greeklish) || json.get("description").toString.toLowerCase.contains(greeklish))) flag2 = false
        if (wordsELOT.size != 0) {
          var wordsELOT2 = new util.ArrayList[String]
          wordsELOT2 = wordsELOT.get({
            j += 1;
            j - 1
          })
          if (wordsELOT2.size == 0) flag3 = false
          else {
            for (greeklishELOT <- wordsELOT2 if !flag3) {
              if (!(json.get("name").toString.toLowerCase.contains(greeklishELOT) || json.get("description").toString.toLowerCase.contains(greeklishELOT))) flag3 = false
              else {
                flag3 = true
              }
            }
          }
        }
        else flag3 = false
      }
      if (flag || flag2 || flag3) pois2.add(json)
    }
    return pois2
  }

  @throws[DatasourceException]
  override def poisByBuildingAsJson2(cuid: String, letters: String): util.List[JsonObject] = {
    var pois: List[JsonObject] = null
    val pois2 = new util.ArrayList[JsonObject]
    pois = allPoisbycuid.get(cuid)
    val words = letters.split(" ")
    var flag = false

    for (json <- pois) {
      flag = true
      val j = 0
      for (w <- words if flag) {
        if (!(json.get("name").toString.toLowerCase.contains(w.toLowerCase) || json.get("description").toString.toLowerCase.contains(w.toLowerCase)))
          flag = false
      }
      if (flag) pois2.add(json)
    }
    pois2
  }

  @throws[DatasourceException]
  override def poisByBuildingAsJson3(buid: String, letters: String): util.List[JsonObject] = {
    var pois: List[JsonObject] = null
    val pois2 = new util.ArrayList[JsonObject]
    if (allPoisSide.get(buid) != null) pois = allPoisSide.get(buid)
    else pois = poisByBuildingAsJson(buid)
    val words = letters.split(" ")
    var flag = false
    var flag2 = false
    var flag3 = false
    if (letters.compareTo(lastletters) != 0) {
      lastletters = letters
      wordsELOT = new util.ArrayList[util.ArrayList[String]]
      var j = 0
      while ( {
        j < words.length
      }) {
        wordsELOT.add(greeklishTogreekList(words(j).toLowerCase))

        {
          j += 1;
          j - 1
        }
      }
    }

    for (json <- pois) {
      flag = true
      flag2 = true
      flag3 = true
      var j = 0
      for (w <- words if !(!flag3 && !flag && !flag2)) {
        if (!(json.get("name").toString.toLowerCase.contains(w.toLowerCase) || json.get("description").toString.toLowerCase.contains(w.toLowerCase))) flag = false
        val greeklish = greeklishTogreek(w.toLowerCase)
        if (!(json.get("name").toString.toLowerCase.contains(greeklish) || json.get("description").toString.toLowerCase.contains(greeklish))) flag2 = false
        if (wordsELOT.size != 0) {
          var wordsELOT2 = new util.ArrayList[String]
          wordsELOT2 = wordsELOT.get({
            j += 1;
            j - 1
          })
          if (wordsELOT2.size == 0) flag3 = false
          else {
            scala.util.control.Breaks.breakable {
              for (greeklishELOT <- wordsELOT2 if !flag3) {
                if (!(json.get("name").toString.toLowerCase.contains(greeklishELOT) || json.get("description").toString.toLowerCase.contains(greeklishELOT))) flag3 = false
                else {
                  flag3 = true
                }
              }
            }
          }
        }
        else flag3 = false
      }
      if (flag || flag2 || flag3) pois2.add(json)
    }
    return pois2
  }

  def greeklishTogreek(greeklish: String) = {
    val myChars = greeklish.toCharArray
    var i = 0
    while ( {
      i < greeklish.length
    }) {
      myChars(i) match {
        case 'a' =>
          myChars(i) = 'α'

        case 'b' =>
          myChars(i) = 'β'

        case 'c' =>
          myChars(i) = 'ψ'

        case 'd' =>
          myChars(i) = 'δ'

        case 'e' =>
          myChars(i) = 'ε'

        case 'f' =>
          myChars(i) = 'φ'

        case 'g' =>
          myChars(i) = 'γ'

        case 'h' =>
          myChars(i) = 'η'

        case 'i' =>
          myChars(i) = 'ι'

        case 'j' =>
          myChars(i) = 'ξ'

        case 'k' =>
          myChars(i) = 'κ'

        case 'l' =>
          myChars(i) = 'λ'

        case 'm' =>
          myChars(i) = 'μ'

        case 'n' =>
          myChars(i) = 'ν'

        case 'o' =>
          myChars(i) = 'ο'

        case 'p' =>
          myChars(i) = 'π'

        case 'q' =>
          myChars(i) = ';'

        case 'r' =>
          myChars(i) = 'ρ'

        case 's' =>
          myChars(i) = 'σ'

        case 't' =>
          myChars(i) = 'τ'

        case 'u' =>
          myChars(i) = 'θ'

        case 'v' =>
          myChars(i) = 'ω'

        case 'w' =>
          myChars(i) = 'ς'

        case 'x' =>
          myChars(i) = 'χ'

        case 'y' =>
          myChars(i) = 'υ'

        case 'z' =>
          myChars(i) = 'ζ'

        case _ =>

      }

      {
        i += 1;
        i - 1
      }
    }
    String.valueOf(myChars)
  }

  def greeklishTogreekList(greeklish: String) = {
    val words = new util.ArrayList[String]
    words.add("")
    val myChars = greeklish.toCharArray
    var i = 0
    while ( {
      i < greeklish.length
    }) {
      val size = words.size
      var j = 0
      while ( {
        j < size
      }) {
        var myword = ""
        myword = words.get(j)
        if (myChars(i) == 'a') words.add(myword + "α")
        else if (myChars(i) == 'b') {
          words.add(myword + "β")
          words.add(myword + "μπ")
        }
        else if (myChars(i) == 'c') {
          if (i < greeklish.length - 1) if (myChars(i + 1) == 'h') words.add(myword + "χ")
          words.add(myword + "γ")
        }
        else if (myChars(i) == 'd') {
          words.add(myword + "δ")
          words.add(myword + "ντ")
        }
        else if (myChars(i) == 'e') {
          words.add(myword + "ε")
          words.add(myword + "αι")
          words.add(myword + "ι")
          words.add(myword + "η")
        }
        else if (myChars(i) == 'f') words.add(myword + "φ")
        else if (myChars(i) == 'g') {
          words.add(myword + "γ")
          words.add(myword + "γγ")
          words.add(myword + "γκ")
        }
        else if (myChars(i) == 'h') {
          if (myword.length > 0 && myword.charAt(myword.length - 1) == 'θ') {
            words.add(myword)

          }
          else if (myword.length > 0 && myword.charAt(myword.length - 1) == 'χ') {
            words.add(myword)

          } else {
            words.add(myword + "χ")
            words.add(myword + "η")
          }
        }
        else if (myChars(i) == 'i') {
          words.add(myword + "ι")
          words.add(myword + "η")
          words.add(myword + "υ")
          words.add(myword + "οι")
          words.add(myword + "ει")
        }
        else if (myChars(i) == 'j') words.add(myword + "ξ")
        else if (myChars(i) == 'k') {
          if (i < greeklish.length - 1) if (myChars(i + 1) == 's') words.add(myword + "ξ")
          words.add(myword + "κ")
        }
        else if (myChars(i) == 'l') words.add(myword + "λ")
        else if (myChars(i) == 'm') words.add(myword + "μ")
        else if (myChars(i) == 'n') words.add(myword + "ν")
        else if (myChars(i) == 'o') {
          words.add(myword + "ο")
          words.add(myword + "ω")
        }
        else if (myChars(i) == 'p') {
          if (i < greeklish.length - 1) if (myChars(i + 1) == 's') words.add(myword + "ψ")
          words.add(myword + "π")
        }
        else if (myChars(i) == 'q') words.add(myword + ";")
        else if (myChars(i) == 'r') words.add(myword + "ρ")
        else if (myChars(i) == 's') {
          if (myword.length > 0 && myword.charAt(myword.length - 1) == 'ξ') {
            words.add(myword)

          } else if (myword.length > 0 && myword.charAt(myword.length - 1) == 'ψ') {
            words.add(myword)
          }
          else {
            words.add(myword + "σ")
            words.add(myword + "ς")
          }
        }
        else if (myChars(i) == 't') {
          if (i < greeklish.length - 1) if (myChars(i + 1) == 'h') words.add(myword + "θ")
          words.add(myword + "τ")
        }
        else if (myChars(i) == 'u') {
          words.add(myword + "υ")
          words.add(myword + "ου")
        }
        else if (myChars(i) == 'v') words.add(myword + "β")
        else if (myChars(i) == 'w') words.add(myword + "ω")
        else if (myChars(i) == 'x') {
          words.add(myword + "χ")
          words.add(myword + "ξ")
        }
        else if (myChars(i) == 'y') words.add(myword + "υ")
        else if (myChars(i) == 'z') words.add(myword + "ζ")

      }

      for (j <- 0 to size) {
        words.remove(0)
      }
    }
    words
  }

  override def poisByBuildingIDAsJson(buid: String): util.List[JsonObject] = {
    val couchbaseClient = getConnection
    val viewQuery = ViewQuery.from("nav", "pois_by_buid").key((buid)).includeDocs(true)

    val res = couchbaseClient.query(viewQuery)
    val result = new util.ArrayList[JsonObject]
    var json: JsonObject = null
    for (row <- res) {
      try {
        json = row.document().content()
        json.removeKey("owner_id")
        json.removeKey("geometry")
        result.add(json)
      } catch {
        case e: IOException =>

        // skip this document since it is not a valid Json object
      }
    }
    result
  }


  @throws[DatasourceException]
  def getAllPoisTypesByOwner(oid: String): util.List[JsonObject] = {
    val poistypes = new util.ArrayList[JsonObject]
    val couchbaseClient = getConnection
    val viewQuery = ViewQuery.from("nav", "all_pois_types").key((oid)).includeDocs(true)

    val res = couchbaseClient.query(viewQuery)
    var json: JsonObject = null
    for (row <- res) {
      try {
        json = row.document().content()
        json.removeKey("owner_id")
        poistypes.add(json)
      } catch {
        case e: Exception =>

        // skip this NOT-JSON document
      }
    }
    poistypes
  }

}
