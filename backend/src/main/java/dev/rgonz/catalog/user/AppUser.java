package dev.rgonz.catalog.user;

/** A person who has signed in, as the app remembers them between logins. */
record AppUser(long id, String subject, String username, String email, String displayName) {}
