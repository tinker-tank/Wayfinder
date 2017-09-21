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
package db_models

import utils.LPUtils
import java.util.HashMap

import com.couchbase.client.java.document.json.JsonObject

class MagneticMilestone(hm: HashMap[String, String]) extends AbstractModel {

    private var json: JsonObject = _

    this.fields = hm

    def this() {
        this(new HashMap[String, String])
        fields.put("buid", "")
        fields.put("floor_num", "")
        fields.put("mmuid", "")
        fields.put("mpuid", "")
        fields.put("lat", "")
        fields.put("lng", "")
        fields.put("ow", "")
        fields.put("ox", "")
        fields.put("oy", "")
        fields.put("oz", "")
        fields.put("fx", "")
        fields.put("fy", "")
        fields.put("fz", "")
        fields.put("doctype", "magnetic_milestone")
    }

    def this(json: JsonObject) {
        this()
        fields.put("buid", json.getString("buid"))
        fields.put("floor_num", json.getString("floor_num"))
        fields.put("mmuid", json.getString("mmuid"))
        fields.put("mpuid", json.getString("mpuid"))
        fields.put("lat", json.getString("lat"))
        fields.put("lng", json.getString("lng"))
        fields.put("ow", json.getString("ow"))
        fields.put("ox", json.getString("ox"))
        fields.put("oy", json.getString("oy"))
        fields.put("oz", json.getString("oz"))
        fields.put("fx", json.getString("fx"))
        fields.put("fy", json.getString("fy"))
        fields.put("fz", json.getString("fz"))
        fields.put("doctype", "magnetic_milestone")
        this.json = json
    }

    def this(json: JsonObject,
             buid: String,
             floor_num: String,
             mpuid: String) {
        this()
        fields.put("buid", buid)
        fields.put("floor_num", floor_num)
        fields.put("mpuid", mpuid)
        fields.put("lat", json.getString("lat"))
        fields.put("lng", json.getString("lng"))
        fields.put("ow", json.getString("ow"))
        fields.put("ox", json.getString("ox"))
        fields.put("oy", json.getString("oy"))
        fields.put("oz", json.getString("oz"))
        fields.put("fx", json.getString("fx"))
        fields.put("fy", json.getString("fy"))
        fields.put("fz", json.getString("fz"))
        fields.put("doctype", "magnetic_milestone")
        this.json = json
    }

    def getId(): String = {
        var id: String = fields.get("mmuid")
        if (id.isEmpty || id== "") {
            id = "mmilestone_" + LPUtils.getRandomUUID + "_" + System.currentTimeMillis()
        }
        id
    }

    def toValidCouchJson(): JsonObject = {
        // initialize id if not initialized
        getId()
        JsonObject.from(this.getFields())
    }

    override def toCouchGeoJSON(): String = toValidCouchJson().toString

    override def toString(): String = this.toValidCouchJson().toString
}
