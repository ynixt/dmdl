package com.ynixt.dmdl.domain.enum

enum class Aria2FileAllocation(
    val aria2Value: String,
) {
    NONE("none"),
    PREALLOC("prealloc"),
    TRUNC("trunc"),
    FALLOC("falloc"),
}
