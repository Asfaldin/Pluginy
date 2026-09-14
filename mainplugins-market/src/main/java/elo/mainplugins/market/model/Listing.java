package elo.mainplugins.market.model;

import java.util.UUID;

/** Oferta na Targu. item = Base64 z ItemStack#serializeAsBytes (zamiana w MarketManager). */
public record Listing(String id, UUID seller, String sellerName, long price, long listedAt, String item) {}
