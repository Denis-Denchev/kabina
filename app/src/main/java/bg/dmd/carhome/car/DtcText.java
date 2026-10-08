package bg.dmd.carhome.car;

import bg.dmd.carhome.App;
import bg.dmd.carhome.R;

/** Short hints for trouble-code families; exact meanings vary by maker. */
final class DtcText {
    private DtcText() { }

    static String describe(String code) {
        if (code.length() < 5) return "";
        char sys = code.charAt(0);
        if (sys == 'U') return App.s(R.string.dtc_network);
        if (sys == 'B') return App.s(R.string.dtc_body);
        if (sys == 'C') return App.s(R.string.dtc_chassis);
        if (code.charAt(1) != '0' && code.charAt(1) != '2') return App.s(R.string.dtc_maker);
        switch (code.charAt(2)) {
            case '0': case '1': case '2': return App.s(R.string.dtc_fuel);
            case '3': return App.s(R.string.dtc_ignition);
            case '4': return App.s(R.string.dtc_emissions);
            case '5': return App.s(R.string.dtc_idle);
            case '6': return App.s(R.string.dtc_ecu);
            case '7': case '8': case '9': return App.s(R.string.dtc_transmission);
            default: return "";
        }
    }
}
