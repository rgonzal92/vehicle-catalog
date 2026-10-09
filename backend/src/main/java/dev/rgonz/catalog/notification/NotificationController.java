package dev.rgonz.catalog.notification;

import dev.rgonz.catalog.core.ApiException;
import dev.rgonz.catalog.notification.Notifications.Inbox;
import dev.rgonz.catalog.user.AppUsers;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Shows each person what they were told, and lets them mark it as read. Nobody sees another's. */
@RestController
class NotificationController {
  private final Notifications notifications;
  private final AppUsers people;

  NotificationController(Notifications notifications, AppUsers people) {
    this.notifications = notifications;
    this.people = people;
  }

  /** The caller's newest notifications, and how many they have not read. */
  @GetMapping("/api/notifications")
  Inbox inbox(Authentication caller) {
    return notifications.inbox(people.idOf(caller));
  }

  /** Marks the caller's notifications as read, up to the one named, and counts what is left. */
  @PostMapping("/api/notifications/read")
  Unread read(@RequestBody UpTo given, Authentication caller) {
    if (given.upTo() == null) {
      throw ApiException.badRequest("Name the notification to mark as read up to.");
    }
    return new Unread(notifications.markRead(people.idOf(caller), given.upTo()));
  }

  /** The newest notification the caller was shown. */
  record UpTo(Long upTo) {}

  /** How many notifications the caller has not read. */
  record Unread(long unread) {}
}
