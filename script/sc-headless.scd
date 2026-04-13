// sc-headless.scd — sclang companion for cljseq SC bridge
// sclang boots scsynth and handles /cmd evaluation explicitly.

s.waitForBoot({
    // Explicit /cmd handler — evaluates SCLang code sent by cljseq send-synthdef!
    OSCdef(\cljseq_cmd, { |msg, time, addr|
        var code = msg[1].asString;
        ("=>[cmd] " ++ code.copyRange(0, 60)).postln;
        code.interpret;
    }, '/cmd');

    "[sc-headless] scsynth booted, /cmd handler active — audio @ %:%".format(
        s.addr.hostname, s.addr.port
    ).postln;
});
