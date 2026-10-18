package com.lookingglass.dash

/**
 * Proper in-game level names for American McGee's Alice (2000), keyed by the
 * game's cryptic map/asset names (the value of [Snapshot.map]).
 *
 * Mirrors the game's own `level_title()` in `src/campaign.rs`, so the dash HUD
 * shows the same names the game uses in its level select — not the dev asset
 * names like "gvillage" or "potears1".
 *
 * The minimap lookup still uses the raw asset name (the PNG files are named by
 * it); only the on-screen text is translated here.
 */
object MapNames {

    val NAMES: Map<String, String> = mapOf(
        "gvillage" to "Dementia — Opening Village",
        "pandemonium" to "Pandemonium",
        "fortress1" to "Fortress of Doors",
        "fortress2" to "Beyond the Wall",
        "skool1" to "Skool Daze",
        "skool2" to "Skool's Out",
        "potears1" to "Pool of Tears",
        "potears2" to "Hollow Hideaway",
        "potears3" to "Just Desserts — Duchess Encounter",
        "utemple" to "Wholly Morel Ground",
        "garden1" to "Dry Landing",
        "garden2" to "Herbaceous Border",
        "garden3" to "Rolling Stones",
        "garden4" to "Icy Reception",
        "centipede1" to "Fungiferous Flora",
        "centipede2" to "Centipede's Sanctum",
        "wforest" to "Caterpillar's Plot",
        "wchess1" to "Pale Realm",
        "wchess2" to "Castling",
        "rchess1" to "Checkmate in Red",
        "funhouse" to "Mirror Image",
        "hatter1" to "Crazed Clockwork",
        "hatter2" to "About Face",
        "jlair1" to "Burning Curiosity",
        "jlair2" to "Jabberwock's Lair",
        "hedge1" to "Majestic Maze",
        "tower1" to "Airborne Terror",
        "hedge2" to "Mystifying Madness",
        "tower2" to "Water Logged",
        "hedge3" to "Labyrinthine Revenge",
        "tower3" to "Machinations",
        "grounds1" to "Royal Rage",
        "grounds2" to "Battle Royale",
        "facade" to "Ascension",
        "keep" to "Castle Keep",
        "qlair" to "Heart of Darkness — Finale",
    )

    /** Proper display name for a raw map/asset name; falls back to the raw name. */
    fun display(raw: String): String = NAMES[raw] ?: raw
}
