package com.codex.aicallguard;

final class CallerLookup {
    static final CallerLookup EMPTY = new CallerLookup(CallerInfo.EMPTY, "");

    final CallerInfo info;
    final String contactName;

    CallerLookup(CallerInfo info, String contactName) {
        this.info = info == null ? CallerInfo.EMPTY : info;
        this.contactName = contactName == null ? "" : contactName;
    }

    boolean isContact() {
        return !contactName.isEmpty();
    }

    String displayName() {
        if (!contactName.isEmpty()) {
            return contactName;
        }
        return info.searchableText();
    }
}
