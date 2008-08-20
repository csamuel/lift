/*
 * Copyright 2007-2008 WorldWide Conferencing, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 */
package bootstrap.liftweb

import net.liftweb.util._
import net.liftweb.http._
import net.liftweb.sitemap._
import net.liftweb.sitemap.Loc._
import Helpers._
import net.liftweb.widgets.calendars._

import net.liftweb.widgets.tree.TreeView

/**
  * A class that's instantiated early and run.  It allows the application
  * to modify lift's environment
  */
class Boot {
  def boot {
    // where to search snippet
    LiftRules.addToPackages("webapptest")

    // Build SiteMap

    val entries = Menu(Loc("Home", List("index"), "Home")) ::
      Menu(Loc("calmonth", List("calmonth"), "CalendarMonthView")) ::
      Menu(Loc("calweek", List("calweek"), "CalendarWeekView")) ::
      Menu(Loc("calday", List("calday"), "CalendarDayView")) ::
      Menu(Loc("rssfeed", List("rssfeed"), "RSSFeed")) ::
      Menu(Loc("gravatear", List("gravatar"), "Gravatar")) ::
      Menu(Loc("tree", List("tree"), "Tree")) ::
      Nil

    LiftRules.setSiteMap(SiteMap(entries:_*))

    CalendarMonthView init;
    CalendarWeekView init;
    CalendarDayView init;
    TreeView init

  }
}

