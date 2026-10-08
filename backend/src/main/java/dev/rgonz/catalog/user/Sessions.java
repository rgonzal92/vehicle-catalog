package dev.rgonz.catalog.user;

import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/** Ends a person's sessions, so that what changed for them applies once they sign in again. */
@Component
class Sessions {
  private final FindByIndexNameSessionRepository<? extends Session> sessions;

  Sessions(FindByIndexNameSessionRepository<? extends Session> sessions) {
    this.sessions = sessions;
  }

  /** Ends every session of the person with the subject, which is what a session names them by. */
  void endOf(String subject) {
    sessions.findByPrincipalName(subject).keySet().forEach(sessions::deleteById);
  }
}
