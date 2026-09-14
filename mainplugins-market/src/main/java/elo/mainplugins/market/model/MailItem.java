package elo.mainplugins.market.model;

/** Przedmiot w skrzynce "Do odebrania". item = Base64 jak w {@link Listing}. */
public record MailItem(long addedAt, String item) {}
