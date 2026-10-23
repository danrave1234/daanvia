package net.drave.atrdiagnostics;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

/** GPX route/waypoint interchange. Track traces are never silently treated as navigable roads. */
final class GpxTrips {
    static List<TripStop> read(InputStream input)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
        while((n=input.read(buffer))!=-1){if(bytes.size()+n>2*1024*1024)throw new IOException("GPX file is too large (maximum 2 MB)");bytes.write(buffer,0,n);}
        String xml=bytes.toString("UTF-8");
        if(xml.contains("<!DOCTYPE")||xml.contains("<!ENTITY"))throw new IOException("GPX document types and entities are not allowed");
        List<TripStop> route=new ArrayList<>(),waypoints=new ArrayList<>();
        SAXParserFactory factory=SAXParserFactory.newInstance();factory.setNamespaceAware(true);
        XMLReader reader=factory.newSAXParser().getXMLReader();
        reader.setEntityResolver((publicId,systemId)->{throw new SAXException("External references are not allowed");});
        reader.setContentHandler(new DefaultHandler(){
            String point=null;double lat,lon;boolean inName;StringBuilder name=new StringBuilder();boolean root;
            public void startElement(String uri,String local,String q,Attributes attributes)throws SAXException{
                String tag=local.isEmpty()?q:local;
                if(!root){root=true;if(!"gpx".equals(tag))throw new SAXException("Choose a GPX file");}
                if("trkpt".equals(tag))throw new SAXException("This file is a track trace. Import a GPX route or waypoints with up to 20 stops.");
                if("rtept".equals(tag)||"wpt".equals(tag)){
                    if(point!=null)throw new SAXException("Nested GPX points are invalid");point=tag;name.setLength(0);
                    try{lat=Double.parseDouble(attributes.getValue("lat"));lon=Double.parseDouble(attributes.getValue("lon"));}catch(RuntimeException e){throw new SAXException("Invalid GPX coordinates");}
                }
                if(point!=null&&"name".equals(tag))inName=true;
            }
            public void characters(char[] chars,int start,int length){if(inName&&name.length()<200)name.append(chars,start,Math.min(length,200-name.length()));}
            public void endElement(String uri,String local,String q)throws SAXException{
                String tag=local.isEmpty()?q:local;if("name".equals(tag))inName=false;
                if(point!=null&&point.equals(tag)){List<TripStop> list="rtept".equals(point)?route:waypoints;if(list.size()>=TripStop.MAX)throw new SAXException("Use at most 20 GPX stops");
                    try{list.add(new TripStop(name.toString(),lat,lon));}catch(IllegalArgumentException e){throw new SAXException(e.getMessage());}point=null;}
            }
        });
        reader.parse(new InputSource(new StringReader(xml)));List<TripStop> out=route.isEmpty()?waypoints:route;
        if(out.isEmpty())throw new IOException("This GPX file contains no route stops or waypoints");return out;
    }
    static void write(OutputStream out,String name,List<TripStop> stops)throws IOException{
        StringBuilder xml=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"Daanvia\" xmlns=\"http://www.topografix.com/GPX/1/1\"><rte><name>").append(escape(name)).append("</name>");
        for(TripStop stop:stops)xml.append("<rtept lat=\"").append(stop.lat).append("\" lon=\"").append(stop.lon).append("\"><name>").append(escape(stop.name)).append("</name></rtept>");
        xml.append("</rte></gpx>\n");out.write(xml.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static String escape(String text){text=text.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]","");return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
}
