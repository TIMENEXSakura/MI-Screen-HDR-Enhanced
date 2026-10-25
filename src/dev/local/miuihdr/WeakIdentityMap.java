package dev.local.miuihdr;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;

/** State belongs to an exact target instance, without retaining editor/controller objects. */
final class WeakIdentityMap<V> {
 private final ReferenceQueue<Object> queue=new ReferenceQueue<>();
 private final HashMap<Key,V> values=new HashMap<>();
 synchronized V get(Object key){clean();return values.get(new Key(key,null));}
 synchronized void put(Object key,V value){
  clean();Key reference=new Key(key,queue);
  if(value==null)values.remove(reference);else values.put(reference,value);
 }
 private void clean(){Key key;while((key=(Key)queue.poll())!=null)values.remove(key);}
 private static final class Key extends WeakReference<Object> {
  private final int hash;
  Key(Object value,ReferenceQueue<Object> queue){super(java.util.Objects.requireNonNull(value),queue);hash=System.identityHashCode(value);}
  @Override public int hashCode(){return hash;}
  @Override public boolean equals(Object other){
   if(this==other)return true;
   if(!(other instanceof Key))return false;
   Object value=get();return value!=null&&value==((Key)other).get();
  }
 }
}
