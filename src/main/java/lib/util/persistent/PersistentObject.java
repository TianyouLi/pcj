/* Copyright (C) 2017  Intel Corporation
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * version 2 only, as published by the Free Software Foundation.
 * This file has been designated as subject to the "Classpath"
 * exception as provided in the LICENSE file that accompanied
 * this code.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License version 2 for more details (a copy
 * is included in the LICENSE file that accompanied this code).
 *
 * You should have received a copy of the GNU General Public License
 * version 2 along with this program; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA  02110-1301, USA.
 */

package lib.util.persistent;

import lib.util.persistent.types.Types;
import lib.util.persistent.types.PersistentType;
import lib.util.persistent.types.ObjectType;
import lib.util.persistent.types.ValueType;
import lib.util.persistent.types.ArrayType;
import lib.util.persistent.types.CarriedType;
import lib.util.persistent.types.ByteField;
import lib.util.persistent.types.ShortField;
import lib.util.persistent.types.IntField;
import lib.util.persistent.types.LongField;
import lib.util.persistent.types.FloatField;
import lib.util.persistent.types.DoubleField;
import lib.util.persistent.types.CharField;
import lib.util.persistent.types.BooleanField;
import lib.util.persistent.types.ObjectField;
import lib.util.persistent.types.ValueField;
import lib.util.persistent.types.PersistentField;
import lib.util.persistent.spi.PersistentMemoryProvider;
import java.util.List;
import java.util.ArrayList;
import java.util.Deque;
import java.util.ArrayDeque;
import java.lang.reflect.Constructor;
import java.util.Iterator;
import lib.xpersistent.XHeap;
import lib.xpersistent.XRoot;
import lib.xpersistent.UncheckedPersistentMemoryRegion;

import sun.misc.Unsafe;

@SuppressWarnings("sunapi")
public class PersistentObject implements Persistent<PersistentObject> {
    static final PersistentHeap heap = PersistentMemoryProvider.getDefaultProvider().getHeap();
    public static Unsafe UNSAFE;
    
    static {
        try {
            java.lang.reflect.Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (Unsafe)f.get(null);
        }
        catch (Exception e) {
            throw new RuntimeException("Unable to initialize UNSAFE.");
        }
    }

    private final ObjectPointer<? extends PersistentObject> pointer;

    public PersistentObject(ObjectType<? extends PersistentObject> type) {
        this(type, PersistentMemoryProvider.getDefaultProvider().getHeap().allocateRegion(type.getAllocationSize()));
    }

    <T extends PersistentObject> PersistentObject(ObjectType<T> type, MemoryRegion region) {
        // trace(region.addr(), String.format("creating object of type %s", type.getName()));
        this.pointer = new ObjectPointer<T>(type, region);
        List<PersistentType> ts = type.getTypes();
        for (int i = 0; i < ts.size(); i++) initializeField(i, ts.get(i));
        Transaction.run(() -> {
            setTypeName(type.getName());
            setVersion(99);
            initForGC();
            if (heap instanceof XHeap && ((XHeap)heap).getDebugMode() == true) {
                ((XRoot)(heap.getRoot())).addToAllObjects(getPointer().region().addr());
            }
        }, this);
        ObjectCache.add(this);
    }

    public PersistentObject(ObjectPointer<? extends PersistentObject> p) {
        // trace(p.region().addr(), "recreating object");
        this.pointer = p;
    }

    void initForGC() {
        Transaction.run(() -> {
            incRefCount();
            ObjectCache.registerObject(this);
        }, this);
    }

    // only called by Root during bootstrap of Object directory PersistentHashMap
    @SuppressWarnings("unchecked")
    public static <T extends PersistentObject> T fromPointer(ObjectPointer<T> p) {
        try {
            Class<T> cls = p.type().cls();
            Constructor ctor = cls.getDeclaredConstructor(ObjectPointer.class);
            ctor.setAccessible(true);
            T obj = (T)ctor.newInstance(p);
            return obj;
        }
        catch (Exception e) {e.printStackTrace();}
        return null;
    }

    static void free(long addr) {
        // trace(addr, "free called");
        ObjectCache.remove(addr);
        MemoryRegion reg = new UncheckedPersistentMemoryRegion(addr);
        MemoryRegion nameRegion = new UncheckedPersistentMemoryRegion(reg.getInt(Header.TYPE.getOffset(Header.TYPE_NAME)));
        Transaction.run(() -> {
            heap.freeRegion(nameRegion);
            heap.freeRegion(reg);
            // trace(addr, "did free regions");
            if (heap instanceof XHeap && ((XHeap)heap).getDebugMode() == true) {
                ((XRoot)(heap.getRoot())).removeFromAllObjects(addr);
            }
            CycleCollector.removeFromCandidates(addr);
        });
    }

    public ObjectPointer<? extends PersistentObject> getPointer() {
        return pointer;
    }

    ObjectType getType() {
        return pointer.type();
    }

    byte getByte(long offset) {return pointer.region().getByte(offset);}
    short getShort(long offset) {return pointer.region().getShort(offset);}
    int getInt(long offset) {return pointer.region().getInt(offset);}
    long getLong(long offset) {return pointer.region().getLong(offset);}

    void setByte(long offset, byte value) {pointer.region().putByte(offset, value);}
    void setShort(long offset, short value) {pointer.region().putShort(offset, value);}
    void setInt(long offset, int value) {pointer.region().putInt(offset, value);}
    void setLong(long offset, long value) {pointer.region().putLong(offset, value);}

    @SuppressWarnings("unchecked")
    synchronized <T extends PersistentObject> T getObject(long offset) {
        long valueAddr = getLong(offset);
        if (valueAddr == 0) return null;
        return (T)ObjectCache.get(valueAddr);
    }

    synchronized void setObject(long offset, PersistentObject value) {
        PersistentObject old = ObjectCache.get(getLong(offset), true);
        Transaction.run(() -> {
            if (value != null) value.addReference();
            if (old != null) old.deleteReference();
            setLong(offset, value == null ? 0 : value.getPointer().addr());
        }, this, value, old);
    }

    byte getByteField(int index) {return getByte(offset(check(index, Types.BYTE)));}
    short getShortField(int index) {return getShort(offset(check(index, Types.SHORT)));}
    int getIntField(int index) {return getInt(offset(check(index, Types.INT)));}
    long getLongField(int index) {return getLong(offset(check(index, Types.LONG)));}
    float getFloatField(int index) {return Float.intBitsToFloat(getInt(offset(check(index, Types.FLOAT))));}
    double getDoubleField(int index) {return Double.longBitsToDouble(getLong(offset(check(index, Types.DOUBLE))));}
    char getCharField(int index) {return (char)getInt(offset(check(index, Types.CHAR)));}
    boolean getBooleanField(int index) {return getByte(offset(check(index, Types.BOOLEAN))) == 0 ? false : true;}
    PersistentObject getObjectField(int index) {return getObject(offset(check(index, Types.OBJECT)));}

    void setByteField(int index, byte value) {setByte(offset(check(index, Types.BYTE)), value);}
    void setShortField(int index, short value) {setShort(offset(check(index, Types.SHORT)), value);}
    void setIntField(int index, int value) {setInt(offset(check(index, Types.INT)), value);}
    void setLongField(int index, long value) {setLong(offset(check(index, Types.LONG)), value);}
    void setFloatField(int index, float value) {setInt(offset(check(index, Types.FLOAT)), Float.floatToIntBits(value));}
    void setDoubleField(int index, double value) {setLong(offset(check(index, Types.DOUBLE)), Double.doubleToLongBits(value));}
    void setCharField(int index, char value) {setInt(offset(check(index, Types.CHAR)), (int)value);}
    void setBooleanField(int index, boolean value) {setByte(offset(check(index, Types.BOOLEAN)), value ? (byte)1 : (byte)0);}
    void setObjectField(int index, PersistentObject value) {setObject(offset(check(index, Types.OBJECT)), value);}

    public byte getByteField(ByteField f) {return getByte(offset(check(f.getIndex(), Types.BYTE)));}
    public short getShortField(ShortField f) {return getShort(offset(check(f.getIndex(), Types.SHORT)));}
    public int getIntField(IntField f) {return getInt(offset(check(f.getIndex(), Types.INT)));}
    public long getLongField(LongField f) {return getLong(offset(check(f.getIndex(), Types.LONG)));}
    public float getFloatField(FloatField f) {return Float.intBitsToFloat(getInt(offset(check(f.getIndex(), Types.FLOAT))));}
    public double getDoubleField(DoubleField f) {return Double.longBitsToDouble(getLong(offset(check(f.getIndex(), Types.DOUBLE))));}
    public char getCharField(CharField f) {return (char)getInt(offset(check(f.getIndex(), Types.CHAR)));}
    public boolean getBooleanField(BooleanField f) {return getByte(offset(check(f.getIndex(), Types.BOOLEAN))) == 0 ? false : true;}
    @SuppressWarnings("unchecked") public <T extends PersistentObject> T getObjectField(ObjectField<T> f) {return (T)getObjectField(f.getIndex());}

    @SuppressWarnings("unchecked")
    public synchronized <T extends PersistentValue> T getValueField(ValueField<T> f) {
        MemoryRegion srcRegion = getPointer().region();
        MemoryRegion dstRegion = heap.allocateRegion(f.getType().getSize());
        // System.out.println(String.format("getValueField src addr = %d, dst addr = %d, size = %d", srcRegion.addr(), dstRegion.addr(), f.getType().getSize()));
        synchronized(srcRegion) {
            synchronized(dstRegion) {
                ((lib.xpersistent.XHeap)heap).memcpy(srcRegion, offset(f.getIndex()), dstRegion, 0, f.getType().getSize());
            }
        }
        return (T)new ValuePointer((ValueType)f.getType(), dstRegion, f.cls()).deref();
    }

    public synchronized <T extends PersistentValue> void setValueField(ValueField<T> f, T value) {
        MemoryRegion dstRegion = getPointer().region();
        long dstOffset = offset(f.getIndex());
        MemoryRegion srcRegion = value.getPointer().region();
        // System.out.println(String.format("setValueField src addr = %d, dst addr = %d, size = %d", srcRegion.addr(), dstRegion.addr() + dstOffset, f.getType().getSize()));
        synchronized(srcRegion) {
            ((lib.xpersistent.XHeap)heap).memcpy(srcRegion, 0, dstRegion, dstOffset, f.getType().getSize());
        }
    }

    public void setByteField(ByteField f, byte value) {setByte(offset(check(f.getIndex(), Types.BYTE)), value);}
    public void setShortField(ShortField f, short value) {setShort(offset(check(f.getIndex(), Types.SHORT)), value);}
    public void setIntField(IntField f, int value) {setInt(offset(check(f.getIndex(), Types.INT)), value);}
    public void setLongField(LongField f, long value) {setLong(offset(check(f.getIndex(), Types.LONG)), value);}
    public void setFloatField(FloatField f, float value) {setInt(offset(check(f.getIndex(), Types.FLOAT)), Float.floatToIntBits(value));}
    public void setDoubleField(DoubleField f, double value) {setLong(offset(check(f.getIndex(), Types.DOUBLE)), Double.doubleToLongBits(value));}
    public void setCharField(CharField f, char value) {setInt(offset(check(f.getIndex(), Types.CHAR)), (int)value);}
    public void setBooleanField(BooleanField f, boolean value) {setByte(offset(check(f.getIndex(), Types.BOOLEAN)), value ? (byte)1 : (byte)0);}
    public <T extends PersistentObject> void setObjectField(ObjectField<T> f, T value) {setObjectField(f.getIndex(), value);}

    // identity beyond one JVM instance
    public final boolean is(PersistentObject obj) {
        return getPointer().region().addr() == obj.getPointer().region().addr();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof PersistentObject && ((PersistentObject)obj).is(this);
    }

    @Override
    public int hashCode() {
        return (int)getPointer().region().addr();
    }

    private List<PersistentType> types() {
        return ((ObjectType<?>)pointer.type()).getTypes();
    }

    private int fieldCount() {
        return types().size();
    }

    private long offset(int index) {
        return ((ObjectType<?>)getPointer().type()).getOffset(index);
    }

    private void initializeField(int index, PersistentType t)
    {
        if (t == Types.BYTE) setByteField(index, (byte)0);
        else if (t == Types.SHORT) setShortField(index, (short)0);
        else if (t == Types.INT) setIntField(index, 0);
        else if (t == Types.LONG) setLongField(index, 0L);
        else if (t == Types.FLOAT) setFloatField(index, 0f);
        else if (t == Types.DOUBLE) setDoubleField(index, 0d);
        else if (t == Types.CHAR) setCharField(index, (char)0);
        else if (t == Types.BOOLEAN) setBooleanField(index, false);
        else if (t instanceof ObjectType) setObjectField(index, null);
    }

    // we can turn this off after debug since only Field-based getters and setters are public
    // and those provide static type safety and internally assigned indexes
    private int check(int index, PersistentType testType) {
        boolean result = true;
        if (index < 0 || index >= fieldCount()) throw new IndexOutOfBoundsException("No such field index: " + index);
        PersistentType t = types().get(index);
        if (t instanceof ObjectType && testType instanceof ObjectType) {
            ObjectType<?> fieldType = (ObjectType)t;
            ObjectType<?> test = (ObjectType) testType;
            if (!test.cls().isAssignableFrom(fieldType.cls())) result = false;
            else if (t != testType) result = false;
            if (!result) throw new RuntimeException("Type mismatch in " + getType().cls() + " at index " + index + ": expected " + testType + ", found " + types().get(index));
        }
        return index;
    }

    private int getVersion() {
        return getIntField(Header.VERSION);
    }

    private void setVersion(int version) {
        setIntField(Header.VERSION, version);
    }

    private void setTypeName(String name) {
        Transaction.run(() -> {
            RawString rs = new RawString(name);
            setLongField(Header.TYPE_NAME, rs.getRegion().addr());
        }, this);
    }

    static String typeNameFromRegion(MemoryRegion region) {
        return new RawString(region).toString();
    }

    int getRefCount() {
        MemoryRegion reg = getPointer().region();
        return reg.getInt(Header.TYPE.getOffset(Header.REF_COUNT));
    }

    void incRefCount() {
        MemoryRegion reg = getPointer().region();
        Transaction.run(() -> {
            int oldCount = reg.getInt(Header.TYPE.getOffset(Header.REF_COUNT));
            reg.putInt(Header.TYPE.getOffset(Header.REF_COUNT), oldCount + 1);
            // trace(getPointer().addr(), String.format("incRefCount(), type = %s, old = %d, new = %d",getPointer().type(), oldCount, getRefCount()));
        }, this);
    }

    int decRefCount() {
        MemoryRegion reg = getPointer().region();
        Box<Integer> newCount = new Box<>();
        Transaction.run(() -> {
            int oldCount = reg.getInt(Header.TYPE.getOffset(Header.REF_COUNT));
            newCount.set(oldCount - 1);
            // trace(getPointer().addr(), String.format("decRefCount, type = %s, old = %d, new = %d", getPointer().type(), oldCount, newCount.get()));
            if (newCount.get() < 0) {
               trace(reg.addr(), "decRef below 0");
               new RuntimeException().printStackTrace(); System.exit(-1);}
            reg.putInt(Header.TYPE.getOffset(Header.REF_COUNT), newCount.get());
        }, this);
        return newCount.get();
    }

    void addReference() {
        Transaction.run(() -> {
            incRefCount();
            setColor(CycleCollector.BLACK);
        }, this);
    }

    synchronized void deleteReference() {
        ArrayList<PersistentObject> toUnlock = new ArrayList<>();
        Deque<Long> addrsToDelete = new ArrayDeque<>();
        MemoryRegion reg = getPointer().region();
        Transaction.run(() -> {
            try {
                int count = 0;
                int newCount = decRefCount();
                if (newCount == 0) {
                    addrsToDelete.push(getPointer().addr());
                    while (!addrsToDelete.isEmpty()) {
                        long addrToDelete = addrsToDelete.pop();
                        Iterator<Long> childAddresses = getChildAddressIterator(addrToDelete);
                        childAddresses = getChildAddressIterator(addrToDelete);
                        while (childAddresses.hasNext()) {
                            long childAddr = childAddresses.next();
                            PersistentObject child = ObjectCache.get(childAddr, true);
                            UNSAFE.monitorEnter(child);
                            toUnlock.add(child);
                            int crc = child.decRefCount();
                            if (crc == 0) {
                                addrsToDelete.push(childAddr);
                            } else {
                                CycleCollector.addCandidate(childAddr);
                            }
                        }
                        free(addrToDelete);
                    }
                } else {
                    CycleCollector.addCandidate(getPointer().addr());
                }
            } 
            finally {
                try {
                    for (int i = toUnlock.size() - 1; i >= 0; i--) {
                            UNSAFE.monitorExit(toUnlock.get(i));
                        }
                }
                catch (IllegalMonitorStateException imse) {
                    throw new RuntimeException(imse.getMessage());
                }
            }
        }, this);
    }

    static void deleteResidualReferences(long address, int count) {
        PersistentObject obj = ObjectCache.get(address, true);
        Transaction.run(() -> {
            int rc = obj.getRefCount();
            // trace(address, String.format("deleteResidualReferences %d, refCount = %d", count, rc));
            assert(obj.getRefCount() >= count);
            for (int i = 0; i < count - 1; i++) obj.decRefCount();
            obj.deleteReference();
        }, obj);
    }

    static String classNameForRegion(MemoryRegion reg) {
        long typeNameAddr = reg.getLong(0);
        MemoryRegion typeNameRegion = new UncheckedPersistentMemoryRegion(typeNameAddr);
        return PersistentObject.typeNameFromRegion(typeNameRegion);
    }

    static Iterator<Long> getChildAddressIterator(long address) {
        MemoryRegion reg = ObjectCache.get(address, true).getPointer().region();
        String typeName = classNameForRegion(reg);
        ObjectType<?> type = Types.typeForName(typeName);

        ArrayList<Long> childAddresses = new ArrayList<>();
        if (type instanceof ArrayType) {
            ArrayType<?> arrType = (ArrayType)type;
            if (arrType.getElementType() == Types.OBJECT) {
                int length = reg.getInt(ArrayType.LENGTH_OFFSET);
                for (int i = 0; i < length; i++) {
                    long childAddr = reg.getLong(arrType.getElementOffset(i));
                    if (childAddr != 0) {
                        childAddresses.add(childAddr);
                    }
                }
            }
        } else if (type instanceof ObjectType) {
            for (int i = Header.TYPE.fieldCount(); i < type.fieldCount(); i++) {
                List<PersistentType> types = type.getTypes();
                if (types.get(i) instanceof ObjectType || types.get(i) == Types.OBJECT) {
                    long childAddr = reg.getLong(type.getOffset(i));
                    if (childAddr != 0) {
                        childAddresses.add(childAddr);
                    }
                }
            }
        } else {
            throw new RuntimeException("getChildAddressIterator: unexpected type");
        }

        return childAddresses.iterator();
    }

    void setColor(byte color) {
        getPointer().region().putByte(Header.TYPE.getOffset(Header.REF_COLOR), color);
    }

    byte getColor() {
        return getPointer().region().getByte(Header.TYPE.getOffset(Header.REF_COLOR));
    }

    // for debugging
    public static boolean enableTrace = false;
    static boolean disableOverride = false;

    static String threadInfo() {
      StringBuilder buff = new StringBuilder();
      long tid = Thread.currentThread().getId();
      for (int i = 0; i < tid; i++) buff.append(" ");
      buff.append(tid);
      return buff.toString();
    }

    public static void trace(long address, String message, boolean... override) {
        if (!disableOverride && (enableTrace || (override.length > 0 && override[0]))) {
            System.out.format("%s: %d " + message + "\n", threadInfo(), address); 
        }
    }
    // end for debugging
}
