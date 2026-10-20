package com.codex.aicallguard;

import android.content.Context;

final class RuleEngine {
    enum Category {
        NONE,
        LANDLINE,
        DELIVERY,
        SALES,
        BANK,
        VIRTUAL,
        SERVICE,
        MOBILE,
        BLACKLIST
    }

    static final class Decision {
        final boolean trigger;
        final Category category;
        final String matched;
        final String reason;

        Decision(boolean trigger, Category category, String matched, String reason) {
            this.trigger = trigger;
            this.category = category;
            this.matched = matched == null ? "" : matched;
            this.reason = reason == null ? "" : reason;
        }
    }

    private static final String[] VIRTUAL_PREFIXES = {
            "162", "165", "167", "170", "171",
            "174", "192", "1349",
            "140", "141", "144", "146", "147", "148", "149"
    };

    private RuleEngine() {
    }

    static Decision classify(
            Context context,
            String rawNumber,
            CallerLookup lookup,
            BlacklistStore.Entry blacklistEntry,
            RuleConfig rules) {
        String digits = digits(rawNumber);
        if (digits.isEmpty()) {
            return new Decision(false, Category.NONE, "", "empty");
        }
        if (isEmergency(digits, context)) {
            return new Decision(false, Category.NONE, digits, "emergency");
        }
        if (blacklistEntry != null) {
            return new Decision(true, Category.BLACKLIST,
                    blacklistEntry.note, "blacklist");
        }
        if (lookup != null && lookup.isContact()) {
            return new Decision(false, Category.NONE, lookup.contactName, "contact_whitelist");
        }

        CallerInfo info = lookup == null ? CallerInfo.EMPTY : lookup.info;
        String text = info.searchableText();
        String matched;

        matched = findKeyword(context, text, R.array.delivery_keywords);
        boolean deliveryCategory = info.categoryId == 5;
        if (rules.delivery && (deliveryCategory || !matched.isEmpty())) {
            return new Decision(true, Category.DELIVERY,
                    deliveryCategory && matched.isEmpty() ? "YellowPage cid 5" : matched,
                    "delivery");
        }

        matched = findKeyword(context, text, R.array.bank_keywords);
        if (rules.bank && !matched.isEmpty()) {
            return new Decision(true, Category.BANK, matched, "bank");
        }

        matched = findKeyword(context, text, R.array.sales_keywords);
        boolean salesCategory = isSalesCategory(info.categoryId);
        if (rules.sales && (salesCategory || !matched.isEmpty())) {
            return new Decision(true, Category.SALES,
                    salesCategory && matched.isEmpty() ? "YellowPage cid " + info.categoryId : matched,
                    "sales");
        }

        matched = findKeyword(context, text, R.array.customer_service_keywords);
        if (rules.customerService && !matched.isEmpty()) {
            return new Decision(true, Category.SERVICE, matched, "service");
        }

        if (rules.virtual && isVirtualNumber(digits)) {
            return new Decision(true, Category.VIRTUAL, virtualPrefix(digits), "virtual");
        }

        boolean mobile = isMobile(digits);
        if (rules.landline && !mobile && isLandlineOrService(digits)) {
            return new Decision(true, Category.LANDLINE, digits, "landline");
        }

        if (rules.unknownMobile && mobile) {
            return new Decision(true, Category.MOBILE, digits, "unknown_mobile_fallback");
        }

        return new Decision(false, Category.NONE, digits, mobile ? "unknown_mobile" : "unmatched");
    }

    static String categoryLabel(Context context, Category category) {
        switch (category) {
            case LANDLINE:
                return context.getString(R.string.category_landline);
            case DELIVERY:
                return context.getString(R.string.category_delivery);
            case SALES:
                return context.getString(R.string.category_sales);
            case BANK:
                return context.getString(R.string.category_bank);
            case VIRTUAL:
                return context.getString(R.string.category_virtual);
            case SERVICE:
                return context.getString(R.string.category_service);
            case MOBILE:
                return context.getString(R.string.category_mobile);
            case BLACKLIST:
                return context.getString(R.string.category_blacklist);
            default:
                return context.getString(R.string.category_none);
        }
    }

    static String reasonLabel(Context context, Decision decision) {
        switch (decision.reason) {
            case "delivery":
                return context.getString(R.string.reason_delivery);
            case "bank":
                return context.getString(R.string.reason_bank);
            case "sales":
                return context.getString(R.string.reason_sales);
            case "service":
                return context.getString(R.string.reason_service);
            case "virtual":
                return context.getString(R.string.reason_virtual, decision.matched);
            case "landline":
                return context.getString(R.string.reason_landline);
            case "unknown_mobile_fallback":
                return context.getString(R.string.reason_unknown_mobile_fallback);
            case "blacklist":
                return context.getString(R.string.reason_blacklist, decision.matched);
            case "contact_whitelist":
                return context.getString(R.string.reason_contact);
            case "emergency":
                return context.getString(R.string.reason_emergency);
            case "unknown_mobile":
                return context.getString(R.string.reason_unknown_mobile);
            case "empty":
                return context.getString(R.string.reason_empty);
            default:
                return context.getString(R.string.reason_unmatched);
        }
    }

    static String digits(String number) {
        if (number == null) {
            return "";
        }
        String value = number.replaceAll("[^0-9]", "");
        if (value.startsWith("0086")) {
            value = value.substring(4);
        } else if (value.startsWith("86") && value.length() > 11) {
            value = value.substring(2);
        }
        return value;
    }

    static boolean isMobile(String digits) {
        return digits.matches("^1[3-9]\\d{9}$") || digits.matches("^1[3-9]\\d{11}$");
    }

    static boolean isVirtualNumber(String digits) {
        return digits.length() >= 11 && virtualPrefix(digits) != null;
    }

    static boolean isLandlineOrService(String digits) {
        if (digits.matches("^0\\d{2,3}\\d{7,8}$")) {
            return true;
        }
        if (digits.matches("^(400|800)\\d{7}$")) {
            return true;
        }
        if (digits.matches("^9[56]\\d{3,5}$")) {
            return true;
        }
        return digits.matches("^10[0-9]\\d{2,4}$");
    }

    private static String virtualPrefix(String digits) {
        for (String prefix : VIRTUAL_PREFIXES) {
            if (digits.startsWith(prefix)) {
                return prefix;
            }
        }
        return null;
    }

    private static boolean isSalesCategory(int cid) {
        return cid == 1 || cid == 3 || cid == 6 || cid == 13 || cid == 14 || cid == 21;
    }

    private static boolean isEmergency(String digits, Context context) {
        for (String entry : context.getResources().getStringArray(R.array.emergency_numbers)) {
            if (digits.equals(entry)) {
                return true;
            }
        }
        return false;
    }

    private static String findKeyword(Context context, String text, int arrayRes) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        for (String keyword : context.getResources().getStringArray(arrayRes)) {
            if (!keyword.isEmpty() && text.contains(keyword)) {
                return keyword;
            }
        }
        return "";
    }
}
