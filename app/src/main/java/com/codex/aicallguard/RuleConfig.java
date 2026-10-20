package com.codex.aicallguard;

final class RuleConfig {
    final boolean landline;
    final boolean delivery;
    final boolean sales;
    final boolean bank;
    final boolean virtual;
    final boolean customerService;
    final boolean unknownMobile;

    RuleConfig(
            boolean landline,
            boolean delivery,
            boolean sales,
            boolean bank,
            boolean virtual,
            boolean customerService,
            boolean unknownMobile) {
        this.landline = landline;
        this.delivery = delivery;
        this.sales = sales;
        this.bank = bank;
        this.virtual = virtual;
        this.customerService = customerService;
        this.unknownMobile = unknownMobile;
    }
}
