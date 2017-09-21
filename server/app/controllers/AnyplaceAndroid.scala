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

import play.api.mvc.Action
import play.mvc.Security
import utils.{AndroidAPKFile, AnyplaceServerAPI}

/**
  * Created by costantinos on 25/6/2017.
  */

import java.io.File
import java.util.{ArrayList, Collections, List}


object AnyplaceAndroid extends play.api.mvc.Controller {

  val ANDROID_APKS_ROOT_DIRECTORY_LOCAL: String = "anyplace_android" + File.separatorChar + "apk" + File.separatorChar

  val ANDROID_APk_DOWNLOAD: String = AnyplaceServerAPI.ANDROID_API_ROOT + "apk/"

  // the action for the Anyplce Architect
  @Security.Authenticated(classOf[Secured])
  def getApks = Action  {
    val dirApks: File = new File(ANDROID_APKS_ROOT_DIRECTORY_LOCAL)
    if (!dirApks.isDirectory || !dirApks.canExecute() || !dirApks.canRead()) {
      BadRequest("No Android apk available!")
    }
    var apk: AndroidAPKFile = null
    val apks: List[AndroidAPKFile] = new ArrayList[AndroidAPKFile]()
    for (fileApk <- dirApks.listFiles()) {
      if (!fileApk.isFile) //continue
        apk = new AndroidAPKFile(fileApk)
      apk.setDownloadUrl(ANDROID_APk_DOWNLOAD + apk.getFilePathBasename)
      apks.add(apk)
    }
    Collections.sort(apks, new AndroidAPKFile.AndroidAPKComparator())
    Ok(views.html.anyplace_android.render(apks))
  }

  def downloadApk(file: String) = Action {
    val fileApk: File = new File(ANDROID_APKS_ROOT_DIRECTORY_LOCAL, file)
    println("requested: " + fileApk)
    if (!fileApk.exists() || !fileApk.canRead()) {
      BadRequest("Requested APK does not exist on our database!")
    }
    Ok.sendFile(fileApk)
  }

}