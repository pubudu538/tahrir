package tahrir.io.serialization;

import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.security.interfaces.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import tahrir.io.serialization.serializers.*;

import com.google.common.collect.Maps;

public abstract class TrSerializer {

	protected final Type type;

	private static Map<Type, TrSerializer> serializers;

	static {
		serializers = new ConcurrentHashMap<Type, TrSerializer>();
		registerSerializer(new IntegerSerializer(), Integer.class, Integer.TYPE);
		registerSerializer(new BooleanSerializer(), Boolean.class, Boolean.TYPE);
		registerSerializer(new ByteSerializer(), Byte.class, Byte.TYPE);
		registerSerializer(new CharSerializer(), Character.class, Character.TYPE);
		registerSerializer(new DoubleSerializer(), Double.class, Double.TYPE);
		registerSerializer(new FloatSerializer(), Float.class, Float.TYPE);
		registerSerializer(new LongSerializer(), Long.class, Long.TYPE);
		registerSerializer(new ShortSerializer(), Short.class, Short.TYPE);
		registerSerializer(new StringSerializer(), String.class);
		registerSerializer(new CollectionSerializer(), Collection.class);
		registerSerializer(new MapSerializer(), Map.class);
		registerSerializer(new RSAPublicKeySerializer(), RSAPublicKey.class);
		registerSerializer(new RSAPrivateKeySerializer(), RSAPrivateKey.class);
	}

	private static final Map<Class<?>, Map<Integer, Field>> fieldMap = Maps.newHashMap();

	public static <T> void registerSerializer(final TrSerializer serializer, final Type... types) {
		for (final Type type : types) {
			final TrSerializer put = serializers.put(type, serializer);
			if (put != null)
				throw new RuntimeException("Tried to register serializer for "+type+" twice");
		}
	}

	protected TrSerializer(final Type type) {
		this.type = type;
	}

	public static TrSerializer getSerializerForType(final Class<?> type) {
		if (type == null || type.equals(Object.class))
			return null;
		final TrSerializer fieldSerializer = serializers.get(type);
		if (fieldSerializer != null) return fieldSerializer;
		for (final Class<?> iface : type.getInterfaces()) {
			final TrSerializer ifaceFS = getSerializerForType(iface);
			if (ifaceFS != null)
				return ifaceFS;
		}
		return getSerializerForType(type.getSuperclass());

	}

	private static Set<Field> getAllFields(final Class<?> c) {
		if (c.equals(Object.class))
			return Collections.emptySet();
		// Ensure a consistent ordering on the fields we return to ensure that
		// digital signatures match as they should
		// FIXME: Is this dangerous? Could lead to weird failures to verify
		// signatures if we aren't careful
		final TreeSet<Field> ret = new TreeSet<Field>(new Comparator<Field>() {

			public int compare(final Field o1, final Field o2) {
				return o1.getName().compareTo(o2.getName());
			}

		});
		for (final Field f : c.getDeclaredFields()) {
			if (Modifier.isStatic(f.getModifiers()) || Modifier.isTransient(f.getModifiers())) {
				// Don't serialize static fields
				continue;
			}
			f.setAccessible(true);
			ret.add(f);
		}
		ret.addAll(getAllFields(c.getSuperclass()));
		return ret;
	}

	public static void serializeTo(final Object object, final ByteBuffer bb) throws TrSerializableException {
		// See if we can serialize directly
		final TrSerializer ts = getSerializerForType(object.getClass());
		if (ts != null) {
			ts.serialize(object.getClass(), object, bb);
		} else {

			try {
				final Set<Field> fields = getAllFields(object.getClass());
				if (fields.size() > 127)
					throw new TrSerializableException("Cannot serialize objects with more than 127 fields");
				byte nonNullFieldCount = 0;
				for (final Field field : fields) {
					if (field.get(object) != null) {
						nonNullFieldCount++;
					}
				}
				bb.put(nonNullFieldCount);
				for (final Field field : fields) {
					field.setAccessible(true);
					final Class<?> fieldType = field.getType();
					final Object fieldObject = field.get(object);

					if (fieldObject == null) {
						continue;
					}

					bb.putInt(field.hashCode());

					if (fieldType.isArray()) {
						final int length = Array.getLength(fieldObject);
						bb.putInt(length);
						for (int x = 0; x < length; x++) {
							serializeTo(Array.get(fieldObject, x), bb);
						}

					} else {
						final TrSerializer fieldSerializer = getSerializerForType(field.getType());
						if (fieldSerializer != null) {
							fieldSerializer.serialize(field.getGenericType(), fieldObject, bb);
						} else {
							if (field.getGenericType() instanceof ParameterizedType)
								throw new TrSerializableException(
								"If you want to serialize a generic type you must register a TahrirSerializer for it");
							serializeTo(fieldObject, bb);
						}


					}
				}
			} catch (final Exception e) {
				throw new TrSerializableException(e);
			}
		}
	}

	@SuppressWarnings("unchecked")
	public static <T> T deserializeFrom(final Class<T> c, final ByteBuffer bb) throws TrSerializableException {
		final TrSerializer ts = getSerializerForType(c);
		if (ts != null)
			return (T) ts.deserialize(c, bb);
		else {
			try {
				Map<Integer, Field> fMap = fieldMap.get(c);
				if (fMap == null) {
					fMap = Maps.newHashMap();
					final Set<Field> fields = getAllFields(c);
					for (final Field field : fields) {
						field.setAccessible(true);
						final Field old = fMap.put(field.hashCode(), field);
						if (old != null) // This is laughably unlikely
							throw new RuntimeException("Field "+field.getName()+" of "+c.getName()+" has the same hashCode() as field "+old.getName()+", one of them MUST be renamed");
					}
					fieldMap.put(c, fMap);
				}
				final T returnObject = c.newInstance();
				final int fieldCount = bb.get();
				for (int fix = 0; fix < fieldCount; fix++) {
					final int fieldHash = bb.getInt();
					final Field field = fMap.get(fieldHash);
					if (field == null)
						throw new TrSerializableException("Unrecognized fieldHash: " + fieldHash);
					if (field.getType().isArray()) {
						final int arrayLen = bb.getInt();
						final Object array = Array.newInstance(field.getType().getComponentType(), arrayLen);
						for (int x = 0; x < arrayLen; x++) {
							Array.set(array, x, deserializeFrom(field.getType().getComponentType(), bb));
						}
						field.set(returnObject, array);
					} else {
						final TrSerializer serializer = getSerializerForType(field.getType());
						if (serializer != null) {
							field.set(returnObject, serializer.deserialize(field.getGenericType(), bb));
						} else {
							field.set(returnObject, deserializeFrom(field.getType(), bb));
						}
					}
				}
				return returnObject;
			} catch (final Exception e) {
				throw new TrSerializableException(e);
			}
		}
	}

	// This code is broken
	// -------------------
	// public static void writeLong(final ByteBuffer bb, long value) {
	// while (value < 0 || value > 127) {
	// bb.put((byte) (0x80 | (value & 0x7F)));
	// value = value >>> 7;
	// }
	// bb.put((byte) value);
	// }
	//
	// public static long readLong(final ByteBuffer bb) throws
	// TahrirSerializableException {
	// int shift = 0;
	// long value = 0;
	// while (true) {
	// final int b = bb.get();
	// if (b < 0) {
	// break;
	// }
	// value = value + (b & 0x7f) << shift;
	// shift += 7;
	// if ((b & 0x80) != 0)
	// return value;
	// }
	// throw new TahrirSerializableException("Malformed stop-bit encoding");
	// }

	protected abstract Object deserialize(Type type, ByteBuffer bb)
	throws TrSerializableException;

	protected abstract void serialize(Type type, Object object, ByteBuffer bb)
	throws TrSerializableException;
}