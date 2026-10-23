package net.drave.atrdiagnostics;

/** Speed-aware prompt timing. A stage is recorded only after audio output accepts it. */
final class GuidancePrompts {
    static final int NONE=0, ADVANCE=1, PREPARE=2, TURN=3, ARRIVAL=4;
    private int maneuver=-1, spokenStage;
    private long lastSpoken=-100000;
    private boolean arrivalSpoken;

    void reset(){maneuver=-1;spokenStage=0;lastSpoken=-100000;arrivalSpoken=false;}
    int next(int turn,double distance,double speed,float accuracy,boolean arrived,long now){
        if(accuracy>40||!Double.isFinite(distance)||distance<0)return NONE;
        if(arrived)return arrivalSpoken?NONE:ARRIVAL;
        if(turn!=maneuver){maneuver=turn;spokenStage=0;}
        double moving=Math.max(0,Math.min(40,Double.isFinite(speed)?speed:0));
        double early=Math.max(250,Math.min(700,moving*35));
        double prepare=Math.max(80,Math.min(250,moving*12));
        double near=Math.max(20,Math.min(65,moving*4));
        int stage=distance<=near?TURN:distance<=prepare?PREPARE:distance<=early?ADVANCE:NONE;
        if(stage<=spokenStage||now-lastSpoken<(stage==TURN?3000:6000))return NONE;
        return stage;
    }
    void spoken(int stage,long now){if(stage==ARRIVAL)arrivalSpoken=true;else spokenStage=stage;lastSpoken=now;}
    static String distance(double meters){int n=meters<100?(int)(Math.round(meters/10)*10):(int)(Math.round(meters/50)*50);return Math.max(10,n)+" metres";}
}
