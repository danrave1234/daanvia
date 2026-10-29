package net.drave.atrdiagnostics;

/** A small, predictable beta command vocabulary; no network interpretation or generated navigation data. */
final class LocalCommands {
    enum Action { REPEAT, ETA, DISTANCE, MUTE, CONFIRM_MUTE, UNMUTE, STOP, CONFIRM_STOP, CANCEL, TRAFFIC, BLOCKED, UNKNOWN }
    static Action match(String transcript){
        String text=SearchIndex.fold(transcript==null?"":transcript);
        text=text.replaceFirst("^(hey )?daanvia ","").replaceFirst("^(please |can you )","").replaceFirst(" please$","");
        if(text.matches(".*\\b(don t|dont|do not|not|never)\\b.*"))return Action.UNKNOWN;
        if(text.matches("(repeat|repeat (that|direction|directions|the (next )?(instruction|direction))|say (that|it) again|what( s| is) (the )?next turn)"))return Action.REPEAT;
        if(text.matches("(eta|arrival time|how long( until (i |we )?(arrive|get there))?|when (will|do) (i|we) (arrive|get there))"))return Action.ETA;
        if(text.matches("(distance|distance remaining|how far( (is it|to go|left))?)"))return Action.DISTANCE;
        if(text.matches("(mute|mute (voice|guidance|directions)|quiet)"))return Action.MUTE;
        if(text.matches("(unmute|unmute (voice|guidance|directions)|turn (voice|guidance) on)"))return Action.UNMUTE;
        if(text.matches("(stop|end|cancel) (navigation|ride|the ride|my ride)"))return Action.STOP;
        if(text.matches("confirm (stop|end|end ride)"))return Action.CONFIRM_STOP;
        if(text.matches("confirm (mute|quiet)"))return Action.CONFIRM_MUTE;
        if(text.matches("(cancel|keep navigating|continue navigation|continue ride)"))return Action.CANCEL;
        if(text.matches("((the )?road (is )?(blocked|closed)|blocked|road closed|road block|roadblock|block (this|the) road)"))return Action.BLOCKED;
        if(text.matches(".*\\btraffic\\b.*"))return Action.TRAFFIC;
        return Action.UNKNOWN;
    }
}
