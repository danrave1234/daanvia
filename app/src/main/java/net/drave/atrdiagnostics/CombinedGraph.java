package net.drave.atrdiagnostics;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Join only tiles from the same immutable map generation. Map/search data stay in their original areas. */
final class CombinedGraph {
    static NavSession.Region choose(File cache,List<NavSession.Region> regions,double a,double b,double c,double d){
        for(NavSession.Region start:regions){
            if(!start.contains(a,b)||!eligible(start))continue;
            List<NavSession.Region> group=new ArrayList<>();boolean end=false;
            for(NavSession.Region r:regions)if(eligible(r)&&start.generation.equals(r.generation)){group.add(r);end|=r.contains(c,d);}
            if(!end||group.size()<2)continue;
            group.sort(Comparator.comparing(r->r.id));
            List<double[]> boxes=new ArrayList<>();StringBuilder key=new StringBuilder(start.generation);
            double[] bounds={180,90,-180,-90};
            for(NavSession.Region r:group){boxes.addAll(Arrays.asList(r.areas));key.append('|').append(r.dir.getAbsolutePath()).append(':').append(new File(r.dir,"region.json").lastModified());
                bounds[0]=Math.min(bounds[0],r.bounds[0]);bounds[1]=Math.min(bounds[1],r.bounds[1]);bounds[2]=Math.max(bounds[2],r.bounds[2]);bounds[3]=Math.max(bounds[3],r.bounds[3]);}
            String id="combined-"+hash(key.toString());
            return new NavSession.Region(id,"Combined offline areas",bounds,boxes.toArray(new double[0][]),null,new File(cache,id),start.generation,group);
        }
        return null;
    }
    private static boolean eligible(NavSession.Region r){return !r.generation.isEmpty()&&r.dir!=null&&new File(r.dir,"valhalla").isDirectory();}
    private static String hash(String text){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(int i=0;i<16;i++)s.append(String.format(Locale.US,"%02x",bytes[i]));return s.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    static void prepare(NavSession.Region r)throws IOException{
        if(r.parts.isEmpty()||new File(r.dir,"ready").isFile())return;
        File tileDir=new File(r.dir,"valhalla");if(!tileDir.isDirectory()&&!tileDir.mkdirs())throw new IOException("Cannot prepare combined maps");
        for(NavSession.Region part:r.parts)merge(new File(part.dir,"valhalla"),tileDir);
        try(FileOutputStream out=new FileOutputStream(new File(r.dir,"ready"))){out.write(1);out.getFD().sync();}
    }
    static void merge(File source,File target)throws IOException{
        File[] files=source.listFiles();if(files==null)throw new IOException("An offline area is no longer available. Download it again.");
        for(File file:files){
            if(Files.isSymbolicLink(file.toPath()))throw new IOException("Invalid routing tile link");
            File to=new File(target,file.getName());
            if(file.isDirectory()){if(!to.isDirectory()&&!to.mkdirs())throw new IOException("Cannot prepare offline tiles");merge(file,to);}
            else if(to.exists()){if(!equal(file,to))throw new IOException("Offline maps contain conflicting tiles. Update the areas together.");}
            else {try{Files.createLink(to.toPath(),file.toPath());}catch(IOException|UnsupportedOperationException e){Files.copy(file.toPath(),to.toPath());}}
        }
    }
    private static boolean equal(File a,File b)throws IOException{
        if(a.length()!=b.length())return false;
        try(InputStream x=new BufferedInputStream(new FileInputStream(a));InputStream y=new BufferedInputStream(new FileInputStream(b))){
            byte[] left=new byte[65536],right=new byte[65536];int n;
            while((n=x.read(left))!=-1){int at=0;while(at<n){int got=y.read(right,at,n-at);if(got<0)return false;at+=got;}for(int i=0;i<n;i++)if(left[i]!=right[i])return false;}return y.read()==-1;
        }
    }
}
