package net.drave.atrdiagnostics;

import android.app.*;
import android.content.*;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;
import static net.drave.atrdiagnostics.Gabi.Role.*;

/** Parked trip preparation is kept out of the map Activity. Android's document picker owns GPX access. */
public final class TripPlannerActivity extends Activity {
    private Gabi.Kit k;private NavSession s;private EditText title,query;
    private LinearLayout rows,results;private TextView status;
    private final ArrayList<TripStop> stops=new ArrayList<>();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable search=this::search;
    private final java.util.concurrent.ExecutorService io=java.util.concurrent.Executors.newSingleThreadExecutor();
    private int revision;
    /** Stops changed since the trip was opened or saved; Back warns once before leaving them. */
    private boolean dirty;private long backWarnedAt,replaceWarnedAt;private String replaceName;
    private static final String NO_MATCH="No downloaded match. Save a place from the map, enter coordinates, or download that area.";
    private NavSession.Places indexedPlaces;private SearchIndex index;private boolean indexing;
    public void onCreate(Bundle state){
        super.onCreate(state);s=NavSession.get(this);k=new Gabi.Kit(this,new Gabi.Themer(Gabi.resolve(this,s.lat,s.lon,s.fixAt>0)));
        GabiPage page=new GabiPage(this,k,"Plan a trip");
        page.content.addView(k.text("Stops are visited in order from your location. Routes use downloaded scooter maps; times exclude stop duration and live traffic.",14,500,MUTED));
        title=edit("Trip name");title.setText("My ride");page.content.addView(title);
        rows=k.column(8);page.content.addView(rows);
        query=edit("Find a stop or enter latitude, longitude");query.setInputType(android.text.InputType.TYPE_CLASS_TEXT);page.content.addView(query);
        query.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int before,int count){main.removeCallbacks(search);main.postDelayed(search,250);}public void afterTextChanged(Editable e){}});
        results=k.column(6);page.content.addView(results);
        status=k.text("",13,500,MUTED);page.content.addView(status);
        page.content.addView(k.ghost("Find fuel stops",()->nearby("fuel")));
        page.content.addView(k.ghost("Find a rest or food stop",()->nearby("rest")));
        page.content.addView(k.primary("Preview trip",()->{
            if(s.active){message("End the ride before changing its stops");return;}
            if(stops.isEmpty()){message("Add a destination first");return;}
            try{dirty=false;startActivity(new Intent(this,HomeActivity.class).putExtra("trip",TripStop.json(stops).toString()).putExtra("trip_name",tripName()).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));finish();}catch(Exception e){message(e.getMessage());}
        }));
        page.content.addView(k.ghost("Save trip on this phone",this::save));
        page.content.addView(k.ghost("Open a saved trip",this::load));
        page.content.addView(k.ghost("Import GPX route or waypoints",()->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),91)));
        page.content.addView(k.ghost("Export stops as GPX",()->{if(stops.isEmpty()){message("Add stops first");return;}startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/gpx+xml").putExtra(Intent.EXTRA_TITLE,"daanvia-trip.gpx"),92);}));
        page.content.addView(k.text("GPX import supports up to 20 route stops or waypoints. Roads are recalculated with your offline maps; a GPX track trace is not turn-by-turn guidance.",12.5f,500,MUTED));
        try{String raw=state!=null?state.getString("stops"):getIntent().getStringExtra("stops");if(raw!=null)stops.addAll(TripStop.parse(raw));else if(!s.active&&!s.shape.isEmpty()){stops.addAll(s.remainingStops());if(stops.isEmpty())stops.add(new TripStop(s.destination,s.destLat,s.destLon));}
            if(state!=null)title.setText(state.getString("title","My ride"));}catch(Exception e){message(e.getMessage());}paint();search();
    }
    private EditText edit(String hint){EditText text=new EditText(this);text.setHint(hint);text.setSingleLine(true);text.setTextSize(16);text.setMinHeight(k.dp(52));text.setPadding(k.dp(12),k.dp(8),k.dp(12),k.dp(8));k.t.add(p->{text.setTextColor(p.ink);text.setHintTextColor(p.muted);text.setBackground(Gabi.round(p.chip,k.dp(12)));});return text;}
    private String tripName(){String value=title.getText().toString().trim();return value.isEmpty()?"My ride":value;}
    private void message(String text){status.setText(text==null?"Could not complete this action":text);}
    private void paint(){
        revision++;rows.removeAllViews();
        if(stops.isEmpty()){rows.addView(k.text("No stops yet · add your final destination first",14,550,MUTED));return;}
        for(int i=0;i<stops.size();i++){final int at=i;TripStop stop=stops.get(i);LinearLayout card=GabiPage.card(k);
            card.addView(k.text((i+1)+" · "+stop.name+(i==stops.size()-1?" · finish":""),15,650,INK));
            LinearLayout buttons=k.row(6);
            if(i>0)buttons.addView(k.ghost("Move up",()->{Collections.swap(stops,at,at-1);dirty=true;paint();}));
            if(i<stops.size()-1)buttons.addView(k.ghost("Move down",()->{Collections.swap(stops,at,at+1);dirty=true;paint();}));
            buttons.addView(k.ghost("Remove",()->{stops.remove(at);dirty=true;paint();}));HorizontalScrollView actions=new HorizontalScrollView(this);actions.setHorizontalScrollBarEnabled(false);actions.addView(buttons);card.addView(actions);rows.addView(card);
        }
    }
    private void add(TripStop stop){if(stops.size()>=TripStop.MAX){message("Use at most 20 stops");return;}stops.add(stop);dirty=true;query.setText("");paint();message("Added "+stop.name+". Move stops to change their order.");}
    private void result(TripStop stop,String detail){results.addView(k.listRow(IconDrawable.PIN,stop.name,detail,null,()->add(stop)));}
    private void search(){
        results.removeAllViews();String q=query.getText().toString().trim();int count=0;
        for(Bookmarks.Place p:new Bookmarks(this).match(q)){result(new TripStop(p.name,p.lat,p.lon),"Saved place");if(++count==5)break;}
        if(q.isEmpty())return;
        try{String[] xy=q.split(",");if(xy.length==2)result(new TripStop("Map coordinates",Double.parseDouble(xy[0].trim()),Double.parseDouble(xy[1].trim())),"Add these coordinates");}catch(IllegalArgumentException ignored){}
        // The place index is built once, off the main thread; the search runs again when it's ready.
        NavSession.Places places=s.places;
        if(indexedPlaces!=places){if(!indexing){indexing=true;io.execute(()->{SearchIndex built=new SearchIndex(places.names,places.lat,places.lon);main.post(()->{indexing=false;index=built;indexedPlaces=places;if(!isFinishing())search();});});}return;}
        for(int i:index.query(q,s.lat,s.lon,6))result(new TripStop(index.names[i],index.lat[i],index.lon[i]),"Downloaded map · "+Gabi.distance(GeoMath.distance(s.lat,s.lon,index.lat[i],index.lon[i]))+" from start");
        if(results.getChildCount()==0)message(NO_MATCH);else if(NO_MATCH.contentEquals(status.getText()))status.setText("");
    }
    private void save(){if(stops.isEmpty()){message("Add stops first");return;}try{JSONObject saved=new JSONObject(getSharedPreferences("trips",0).getString("saved","{}"));
        if(saved.has(tripName())&&!(tripName().equals(replaceName)&&SystemClock.elapsedRealtime()-replaceWarnedAt<6000)){replaceName=tripName();replaceWarnedAt=SystemClock.elapsedRealtime();message("A trip named "+tripName()+" is already saved. Tap Save again to replace it, or change the name.");return;}
        replaceName=null;dirty=false;saved.put(tripName(),TripStop.json(stops));getSharedPreferences("trips",0).edit().putString("saved",saved.toString()).apply();message("Saved "+tripName()+" on this phone");}catch(Exception e){message(e.getMessage());}}
    private void load(){try{JSONObject saved=new JSONObject(getSharedPreferences("trips",0).getString("saved","{}"));List<String> keys=new ArrayList<>();saved.keys().forEachRemaining(keys::add);Collections.sort(keys);if(keys.isEmpty()){message("No saved trips yet");return;}new AlertDialog.Builder(this).setTitle("Open trip").setItems(keys.toArray(new String[0]),(d,which)->{try{stops.clear();stops.addAll(TripStop.parse(saved.getJSONArray(keys.get(which)).toString()));title.setText(keys.get(which));dirty=false;paint();}catch(Exception e){message(e.getMessage());}}).setNeutralButton("Delete a trip",(d,w)->deleteTrip(keys)).show();}catch(Exception e){message(e.getMessage());}}
    /** Pick a saved trip to delete, then confirm. */
    private void deleteTrip(List<String> keys){
        new AlertDialog.Builder(this).setTitle("Delete which trip?").setItems(keys.toArray(new String[0]),(d,which)->{String name=keys.get(which);
            new AlertDialog.Builder(this).setTitle("Delete "+name+"?").setMessage("This removes the saved trip from this phone.").setNegativeButton("Keep",null)
                .setPositiveButton("Delete",(d2,w2)->{try{JSONObject saved=new JSONObject(getSharedPreferences("trips",0).getString("saved","{}"));saved.remove(name);getSharedPreferences("trips",0).edit().putString("saved",saved.toString()).apply();message("Deleted "+name);}catch(Exception e){message(e.getMessage());}}).show();
        }).setNegativeButton("Cancel",null).show();
    }
    /** Leaving with stops that aren't saved: warn once; a second Back within a few seconds leaves. */
    public void onBackPressed(){
        if(dirty&&!stops.isEmpty()&&SystemClock.elapsedRealtime()-backWarnedAt>4000){backWarnedAt=SystemClock.elapsedRealtime();message("Your stops aren't saved. Tap Save trip, or press Back again to leave without saving.");return;}
        super.onBackPressed();
    }
    private void nearby(String kind){
        if(stops.isEmpty()){message("Add your destination first, then find a stop on the way");return;}
        int token=++revision;message("Finding mapped stops and calculating road detours…");results.removeAllViews();List<TripStop> original=new ArrayList<>(stops);
        io.execute(()->{try{List<TripStop> found=NearbyStops.find(this,kind,s.lat,s.lon);s.detours(original,found,estimates->main.post(()->{if(isFinishing()||token!=revision)return;results.removeAllViews();for(NavSession.Detour estimate:estimates){TripStop stop=estimate.stop;results.addView(k.listRow(IconDrawable.PIN,stop.name,"Adds about "+Gabi.minutes(estimate.seconds)+" · "+Gabi.distance(estimate.meters)+" of riding",null,()->{if(stops.size()>=TripStop.MAX){message("Use at most 20 stops");return;}stops.add(Math.max(0,stops.size()-1),stop);paint();results.removeAllViews();message("Added before the finish. Preview the route and check access and opening hours.");}));}message(estimates.isEmpty()?"No reachable stops found in these offline maps":"OpenStreetMap or cached places · detours use offline scooter roads. Check hours, access and services when you arrive.");}));}catch(Exception e){main.post(()->{if(token==revision)message(e.getMessage());});}});
    }
    protected void onActivityResult(int code,int result,Intent data){super.onActivityResult(code,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;if(code==91)dirty=true;android.net.Uri uri=data.getData();List<TripStop> snapshot=new ArrayList<>(stops);String name=tripName();io.execute(()->{try{if(code==91){List<TripStop> imported;try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("Cannot read GPX");imported=GpxTrips.read(in);}main.post(()->{stops.clear();stops.addAll(imported);paint();message("Imported stops. Preview the recalculated roads before riding.");});}else if(code==92){try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException("Cannot save GPX");GpxTrips.write(out,name,snapshot);}main.post(()->message("GPX saved"));}}catch(Exception e){main.post(()->message(e.getMessage()));}});}
    protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);try{out.putString("stops",TripStop.json(stops).toString());out.putString("title",tripName());}catch(JSONException ignored){}}
    protected void onDestroy(){revision++;main.removeCallbacksAndMessages(null);io.shutdownNow();super.onDestroy();}
}
