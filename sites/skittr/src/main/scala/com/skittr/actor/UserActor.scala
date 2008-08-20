package com.skittr.actor

/*                                                *\
 (c) 2007 WorldWide Conferencing, LLC
 Distributed under an Apache License
 http://www.apache.org/licenses/LICENSE-2.0
\*                                                 */

import scala.actors._
import scala.actors.Actor._
import com.skittr.model._
import scala.collection.mutable.{HashMap}
import net.liftweb.mapper._
import java.util.concurrent.locks.ReentrantReadWriteLock
import net.liftweb.util._
import net.liftweb.util.Helpers._



/**
 * All the "current state" and logic necessary to deal with messages between users
 */
class UserActor extends Actor {
  // the maximum messages to keep in memory
  private val maxMessages = 20

  // Information about the user
  private var userName: String = _
  private var userId: Long = _
  private var fullName: String = _

  // the list of the latest messages for the user
  private var latestMsgs: List[Message] = Nil

  // The timeline for the current user
  private var localTimeline: List[Message] = Nil

  // the folks who are following this user
  private var followers: List[Actor] = Nil

  // the listeners (either message listeners and/or listeners to the timeline)
  private var messageViewers: List[Actor] = Nil
  private var timelineViewers: List[Actor] = Nil

  // a list of friends
  private var friends: List[String] = Nil

  /**
   * When the Actor is started, this method is invoked
   */
  def act = {
    this.trapExit = true // send messages on exit of linked Actors

    // The main loop that receives messages and processes them
    loop {
      react {
        // The user sends a message containing text and a source.  This can
        // be from the web, from IM, from SMS, etc.
        case SendMessage(text, src) =>
          // create a new Message object to be added to the user's local message and sent
          // to followers
          val msg = Message(text, System.currentTimeMillis, userName, src)
          // add to our local messages (keeping only the maxMessages most recent messages)
          latestMsgs = (msg :: latestMsgs).take(maxMessages)
          // update all our followers (and ourselves) with the message
          (this :: followers).foreach(_ ! msg)
          // and put it in the database
          MsgStore.create.message(text).source(src).who(userId).save
          // if the message was autogenerated, then autogenerate another message in 5 or so minutes
          if (src == "autogen") autoGen

        // someone is asking us for our messages
        case GetMessages => reply(Messages(latestMsgs)) // send them back

        // someone is asking us for our timeline
        case GetTimeline => reply(Timeline(localTimeline))  // send it back

        // someone wants to know our name... tell them
        case GetUserIdAndName => reply(UserIdInfo(userId, userName, fullName, friends))

        // shut the user down
        case Bye =>
          UserList.remove(userName)
          this.exit(Bye)

        // set up the user
        case Setup(id, name, full) =>
          userId = id
          userName = name
          fullName = full
          UserList.add(userName, this) // add the user to the list
          // get the latest messages from the database
          latestMsgs = MsgStore.findAll(By(MsgStore.who, userId),
                                      OrderBy(MsgStore.when, Descending),
                                      MaxRows(maxMessages)).map(s => Message(s.message, s.when, userName, s.source))
          localTimeline = latestMsgs  // set the local timeline to our messages (the folks we follow will update us)
          // get friends
          friends = User.findAllByInsecureSql("SELECT users.* FROM users, friends WHERE users.id = friends.friend AND friends.owner = "+userId,
					    true).map(_.name.is).sort(_ < _)
          reply("Done")

        // tell all our friends that we follow them
        case ConfigFollowers =>
          friends.flatMap(f => UserList.find(f).toList).foreach(_ ! AddFollower)
          // if the "autogen" property is set, then have each of the actors
          // randomly generate a message
          if (User.shouldAutogen_? || System.getProperty("autogen") != null) autoGen

          reply("Done")

        // if we add a friend,
        case AddFriend(name) =>
          friends = (name :: friends).sort(_ < _)
          // find the user
          UserList.find(name).foreach{
            ua =>
            ua ! AddFollower // tell him we're a follower
            (ua !? GetUserIdAndName) match { // get the user info
              case UserIdInfo(id, _,_, _) => Friend.create.owner(userId).friend(id).save // and persist a friend connection in the DB
              case _ =>
            }
          }

        // We are removing a friend
        case RemoveFriend(name) =>
          friends = friends.remove(_ == name)
          // find the user
          UserList.find(name).foreach{
            ua =>
              ua ! RemoveFollower // tell them we're no longer following
              (ua !? GetUserIdAndName) match { // delete from database
		case UserIdInfo(id, _,_,_) => Friend.findAll(By(Friend.owner, userId), By(Friend.friend, id)).foreach(_.delete_!)
		case _ =>
              }
          }
          // remove from local timeline
          localTimeline = localTimeline.filter(_.who != name)
          // update timeline vieweres with the former-friend-free timeline
          timelineViewers.foreach(_ ! Timeline(localTimeline))

        // merge the messages (from a friend) into our local timeline
        case MergeIntoTimeline(msg) => localTimeline = merge(localTimeline ::: msg)

        // add someone who is watching the timeline.  This Actor will get updates each time
        // the local timeline updates.  We link to them so we can remove them if they exit
        case AddTimelineViewer =>
          timelineViewers = sender.receiver :: timelineViewers
          this.link(sender.receiver)

        // remove the timeline viewer
        case RemoveTimelineViewer =>
          timelineViewers = timelineViewers.remove(_ == sender.receiver)
          this.unlink(sender.receiver)

        // Add an Actor to the list of folks who want to see when we get a message
        // this might be an IM or SMS output
        case AddMessageViewer =>
          messageViewers = sender.receiver :: messageViewers
          this.link(sender.receiver)

        // removes the message viewer
        case RemoveMessageViewer =>
          messageViewers = messageViewers.remove(_ == sender.receiver)
          this.unlink(sender.receiver)

        // add someone who is following us
        case AddFollower =>
          followers = sender.receiver :: followers // merge it in
          sender.receiver ! MergeIntoTimeline(latestMsgs) // give the follower our messages to merge into his timeline

        // remove the follower
        case RemoveFollower =>  followers = followers.remove(_ == sender.receiver) // filter out the sender of the message

        // We get a message
        case msg : Message =>
          messageViewers.foreach(_ ! Messages(latestMsgs)) // send it to the message viewers
          localTimeline = (msg :: localTimeline).take(maxMessages) // update our timeline
          timelineViewers.foreach(_ ! Timeline(localTimeline)) // send the updated timeline to the timeline viewers

        // If someone is exiting, remove them from our lists
        case Exit(who, why) =>
          messageViewers = messageViewers.remove(_ == who)
          timelineViewers = timelineViewers.remove(_ == who)
          Log.info("Exitted from actor "+who+" why "+why)

        case s => Log.info("User "+userName+" Got msg "+s)
      }
    }
  }

  /**
  * Sort the list in reverse chronological order and take the first maxMessages elements
  */
  private def merge(bigList: List[Message]) = bigList.sort((a,b) => b.when < a.when).take(maxMessages)

  /**
  * Autogenerate and schedule a message
  */
  def autoGen = ActorPing.schedule(this, SendMessage("This is a random message @ "+timeNow+" for "+userName, "autogen"), User.randomPeriod)
}

/**
* These are the messages that can be sent to (or from) a UserActor
*/
sealed abstract class UserMsg

/**
* Send a message to a User (from the web, from IM, from SMS).  The Actor
* will take care of persisting the information in the database.
*
* @param text - the text of the message
* @param src - the source of the message
*/
case class SendMessage(text: String, src: String) extends UserMsg

/**
* A message
* @param text - the text of the message
* @param when - when was the message processed by the user object (about the time it was sent)
* @param who - who sent the message (the user name)
* @param src - how was the message sent
*/
case class Message(text: String, when: Long, who: String, src: String) extends UserMsg

/**
* Tell the UserActor to set itself up with the given user id, name, and full name
* @param userId - the primary key of the user in the database
* @param userName - the name of the user (e.g., john)
* @param fullName - the first and last name of the user "John Q. Public"
*/
case class Setup(userId: Long, userName: String, fullName: String) extends UserMsg

/**
* Add a timeline viewer
*/
case object AddTimelineViewer extends UserMsg

/**
* Remove a timeline viewer
*/
case object RemoveTimelineViewer extends UserMsg

/**
* Add a message viewer
*/
case object AddMessageViewer extends UserMsg

/**
* Remove a message viewer
*/
case object RemoveMessageViewer extends UserMsg

/**
* Add a follower to this User
*/
case object AddFollower extends UserMsg

/**
* Remove a follower
*/
case object RemoveFollower extends UserMsg

/**
* Sent from a user to a follower telling the follower to merge the user's messages
* into the followers timeline
* @param what the messages to merge into the timeline
*/
case class MergeIntoTimeline(what: List[Message]) extends UserMsg

/**
* Get the messages from the user
*/
case object GetMessages extends UserMsg

/**
* Get the timeline from the user
*/
case object GetTimeline extends UserMsg

/**
* Tell the user to gracefully shut itself down
*/
case object Bye extends UserMsg

/**
* Tell the user to load a list of followers and and send them the user's messages to merge
* into the follower's timeline
*/
case object ConfigFollowers extends UserMsg

/**
* Ask the user for the userId, the name, and the fullName.  The user will
* reply with a UserIdInfo message
*/
case object GetUserIdAndName extends UserMsg

/**
 * Send the current timeline
 * @param message - the local timeline for the user
 */
case class Timeline(messages: List[Message]) extends UserMsg

/**
 * The current messages for the user
 * @param messages a list of messages for the user
 */
case class Messages(messages: List[Message]) extends UserMsg

/**
 * Add a friend to the current user and persist the information
 * in the database.
 * @param name the name of the user who is our new friend
 */
case class AddFriend(name: String) extends UserMsg

/**
 * Remove a friend and update the database.
 * @param name the name of the friend to remove
 */
case class RemoveFriend(name: String) extends UserMsg

/**
 * Returned from GetUserIdAndName
 * @param id the id/primary key of the user
 * @param name the user name
 * @param fullName the full name of the user
 */
case class UserIdInfo(id: Long, name: String, fullName: String, friends: List[String])

case object SendRandomMessage
