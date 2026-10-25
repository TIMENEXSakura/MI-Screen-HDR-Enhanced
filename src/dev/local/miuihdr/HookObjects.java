package dev.local.miuihdr;
import java.lang.reflect.*;
import java.util.*;

/** Java reflection and module-owned weak state; no framework helper dependency. */
final class HookObjects {
 private static final WeakHashMap<Object,Map<String,Object>> STATE=new WeakHashMap<>();
 static synchronized Object getAdditionalInstanceField(Object owner,String key){Map<String,Object> values=STATE.get(owner);return values==null?null:values.get(key);}
 static synchronized void setAdditionalInstanceField(Object owner,String key,Object value){
  if(value==null){Map<String,Object> values=STATE.get(owner);if(values!=null){values.remove(key);if(values.isEmpty())STATE.remove(owner);}return;}
  STATE.computeIfAbsent(owner,k->new HashMap<>()).put(key,value);
 }
 private static Field field(Object owner,String name){
  for(Class<?> c=owner.getClass();c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException ignored){}
  throw new IllegalArgumentException("Field unavailable: "+name);
 }
 static Object getObjectField(Object owner,String name){try{return field(owner,name).get(owner);}catch(IllegalAccessException e){throw new IllegalStateException(e);}}
 static void setObjectField(Object owner,String name,Object value){try{field(owner,name).set(owner,value);}catch(IllegalAccessException e){throw new IllegalStateException(e);}}
 static Object callMethod(Object owner,String name,Object...args){
  Class<?>[] types=new Class<?>[args.length];for(int i=0;i<args.length;i++)types[i]=args[i] instanceof Boolean?boolean.class:args[i].getClass();
  for(Class<?> c=owner.getClass();c!=null;c=c.getSuperclass())try{Method m=c.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(owner,args);}
  catch(NoSuchMethodException ignored){}catch(ReflectiveOperationException e){throw new IllegalStateException(name,e);}
  throw new IllegalArgumentException("Method unavailable: "+name);
 }
 static android.content.Context currentApplication(){
  try{return (android.content.Context)Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication").invoke(null);}
  catch(ReflectiveOperationException e){return null;}
 }
}
