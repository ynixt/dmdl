package com.ynixt.dmdl.domain.gateway

import com.ynixt.dmdl.domain.model.AnimeVerdict

/** An index that only lists anime: finding a title there proves it is an anime. */
interface AnimeIndexGateway {
    /** [ids] maps providers (`mal`, `anilist`, `anidb`, `tvdb`) to their ids. Never throws. */
    fun lookup(ids: Map<String, String>): AnimeVerdict
}
