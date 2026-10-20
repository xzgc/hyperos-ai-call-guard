package com.codex.aicallguard;

final class CallerInfo {
    static final CallerInfo EMPTY = new CallerInfo("", "", -1);

    final String tag;
    final String name;
    final int categoryId;

    CallerInfo(String tag, String name, int categoryId) {
        this.tag = tag == null ? "" : tag;
        this.name = name == null ? "" : name;
        this.categoryId = categoryId;
    }

    String searchableText() {
        return (tag + " " + name).trim();
    }
}
