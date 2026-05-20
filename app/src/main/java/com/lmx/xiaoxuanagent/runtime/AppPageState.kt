package com.lmx.xiaoxuanagent.runtime

enum class AppPageState(val label: String) {
    Home("SHOPPING_HOME"),
    SearchInput("SHOPPING_SEARCH_INPUT"),
    SearchResultWeak("SHOPPING_SEARCH_RESULT_WEAK"),
    ProductDetail("SHOPPING_PRODUCT_DETAIL"),
    ProductReview("SHOPPING_PRODUCT_REVIEW"),
    ProductParam("SHOPPING_PRODUCT_PARAM"),
    Unknown("UNKNOWN"),
}
