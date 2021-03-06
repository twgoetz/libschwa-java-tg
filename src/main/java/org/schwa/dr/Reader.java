package org.schwa.dr;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.msgpack.core.buffer.InputStreamBufferInput;
import org.msgpack.core.MessagePackFactory;
import org.msgpack.core.MessagePacker;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.holder.ValueHolder;

import org.schwa.dr.runtime.RTAnnSchema;
import org.schwa.dr.runtime.RTFactory;
import org.schwa.dr.runtime.RTFieldSchema;
import org.schwa.dr.runtime.RTManager;
import org.schwa.dr.runtime.RTStoreSchema;


/**
 * Reads in docrep documents of type T from the input stream provided in the constructor.
 * This class implements the {@link Iterator} interface, providing an interator for that yields
 * documents of type T.
 *
 * @author Tim Dawborn
 **/
public final class Reader <T extends Doc> implements Iterable<T>, Iterator<T> {
  /** docrep wire protocol version that this reader knows how to read. **/
  public static final byte WIRE_VERSION = 3;

  private final ByteArrayInputStream in;
  private final DocSchema docSchema;
  private final MessageUnpacker unpacker;
  private T doc;

  /**
   * Constructs a new docrep reader given the input stream and document schema.
   *
   * @param in The input stream to read from. This needs to be an input stream which has full
   *           {@link java.io.InputStream#mark} support to implement lazy loading correctly. To
   *           ensure this is the case, the input stream must currently be a
   *           {@link ByteArrayInputStream}.
   * @param docSchema The {@link DocSchema} instance to use for reading. Unlike the other docrep
   *                  APIs, this argument cannot be optional as you cannot be the .class attribute
   *                  of a generic type due to type erasure.
   **/
  public Reader(ByteArrayInputStream in, DocSchema docSchema) {
    this.in = in;
    this.docSchema = docSchema;
    this.unpacker = new MessageUnpacker(new InputStreamBufferInput(in, 1));  // We don't want the InputStreamBufferInput to do any buffering since we need to refer to the underlying ByteArrayInputStream directly to implement lazyness.
    readNext();
  }

  /**
   * Returns this same object as an {@link Iterator} to comply with the {@link Iterable} interface.
   **/
  @Override  // Iterable<T>
  public Iterator<T> iterator() {
    return this;
  }

  /**
   * Returns whether or not the reader iterator has any more documents to read in from the input
   * stream.
   **/
  @Override  // Iterator<T>
  public boolean hasNext() {
    return doc != null;
  }

  /**
   * Reads the next document from the input stream and returns it.
   **/
  @Override  // Iterator<T>
  public T next() {
    final T doc = this.doc;
    readNext();
    return doc;
  }

  /**
   * This optional method from {@link Iterator} is unsupported.
   *
   * @throws UnsupportedOperationException
   **/
  @Override  // Iterator<T>
  public void remove() {
    throw new UnsupportedOperationException();
  }

  private void readNext() {
    try {
      _readNext();
    }
    catch (IOException e) {
      throw new ReaderException(e);
    }
    catch (InstantiationException e) {
      throw new DocrepException(e);
    }
    catch (IllegalAccessException e) {
      throw new DocrepException(e);
    }
  }

  private void _readNext() throws IOException, InstantiationException, IllegalAccessException {
    // <doc>  ::= <wire_version> <klasses> <stores> <doc_instance> <instances_groups>

    // Read the wire format version before, and ensure we know how to read that version.
    // <wire_version> ::= UINT
    byte wireVersion;
    try {
      wireVersion = unpacker.unpackByte();
    }
    catch (EOFException e) {
      doc = null;
      return;
    }
    if (wireVersion != WIRE_VERSION)
      throw new ReaderException("Invalid wire format version. Stream has version " + wireVersion + " but I can only read version " + WIRE_VERSION + ". Ensure the input is not plain text.");

    // Construct the lazy runtime manager for the document.
    final RTManager rt = RTFactory.create();
    doc = (T) docSchema.getKlass().newInstance();
    doc.setDRRT(rt);

    // Map of each of the registered types.
    Map<String, AnnSchema> klassNameMap = new HashMap<String, AnnSchema>();
    klassNameMap.put("__meta__", docSchema);
    for (AnnSchema ann : docSchema.getSchemas())
      klassNameMap.put(ann.getSerial(), ann);

    // Keep track of the temporary mapping of pointer field to the store_id that they point to.
    Map<RTFieldSchema, Integer> rtFieldSchemaToStoreIds = new HashMap<RTFieldSchema, Integer>();

    // Keep track of the klass_id of __meta__.
    Integer klassIdMeta = null;

    // Read the klasses header.
    // <klasses> ::= [ <klass> ]
    final int nklasses = unpacker.unpackArrayHeader();
    for (int k = 0; k != nklasses; k++) {
      // <klass> ::= ( <klass_name>, <fields> )
      final int npair = unpacker.unpackArrayHeader();
      if (npair != 2)
        throw new ReaderException("Invalid sized tuple read in: expected 2 elements but found " + npair);

      // Read in the class name and check that we have a registered class with this name.
      RTAnnSchema rtAnnSchema;
      final String klassName = unpacker.unpackString();
      final AnnSchema schema = klassNameMap.get(klassName);
      if (schema == null)
        rtAnnSchema = new RTAnnSchema(k, klassName);
      else
        rtAnnSchema = new RTAnnSchema(k, klassName, schema);
      rt.addAnn(rtAnnSchema);

      // Keep track of the klass_id of __meta__.
      if (klassName.equals("__meta__"))
        klassIdMeta = k;

      // <fields> ::= [ <field> ]
      final int nfields = unpacker.unpackArrayHeader();
      for (int f = 0; f != nfields; f++) {
        String fieldName = null;
        int storeId = -1;
        boolean isPointer = false, isSelfPointer = false, isSlice = false, isCollection = false;

        // <field> ::= { <field_type> : <field_val> }
        final int nitems = unpacker.unpackMapHeader();
        for (int i = 0; i != nitems; ++i) {
          final byte key = unpacker.unpackByte();
          switch (key) {
          case 0:  // NAME
            fieldName = unpacker.unpackString();
            break;
          case 1:  // POINTER_TO
            storeId = unpacker.unpackInt();
            isPointer = true;
            break;
          case 2:  // IS_SLICE
            unpacker.unpackNil();
            isSlice = true;
            break;
          case 3:  // IS_SELF_POINTER
            unpacker.unpackNil();
            isSelfPointer = true;
            break;
          case 4:  // IS_COLLECTION
            unpacker.unpackNil();
            isCollection = true;
            break;
          default:
            throw new ReaderException("Unknown value " + ((int) key) +  " as key in <field> map");
          }
        }  // for each field.
        if (fieldName == null)
          throw new ReaderException("Field number " + (f + 1) + " did not contain a NAME key");

        // See if the read in field exists on the registered class's schema.
        RTFieldSchema rtFieldSchema;
        if (rtAnnSchema.isLazy())
          rtFieldSchema = new RTFieldSchema(f, fieldName, null, isCollection, isSelfPointer, isSlice);
        else {
          // Try and find the field on the registered class.
          FieldSchema fieldDef = null;
          for (FieldSchema field : rtAnnSchema.getDef().getFields()) {
            if (field.getSerial().equals(fieldName)) {
              fieldDef = field;
              break;
            }
          }
          rtFieldSchema = new RTFieldSchema(f, fieldName, null, isCollection, isSelfPointer, isSlice, fieldDef);

          // Perform some sanity checks that the type of data on the stream is what we're expecting.
          if (fieldDef != null) {
            if (isPointer != fieldDef.isPointer())
              throw new ReaderException("Field '" + fieldName + "' of class '" + klassName + "' has IS_POINTER as " + isPointer + " on the stream, but " + fieldDef.isPointer() + " on the class's field");
            if (isSlice != fieldDef.isSlice())
              throw new ReaderException("Field '" + fieldName + "' of class '" + klassName + "' has IS_SLICE as " + isSlice + " on the stream, but " + fieldDef.isSlice() + " on the class's field");
            if (isSelfPointer != fieldDef.isSelfPointer())
              throw new ReaderException("Field '" + fieldName + "' of class '" + klassName + "' has IS_SELF_POINTER as " + isSelfPointer + " on the stream, but " + fieldDef.isSelfPointer() + " on the class's field");
            if (isCollection != fieldDef.isCollection())
              throw new ReaderException("Field '" + fieldName + "' of class '" + klassName + "' has IS_COLLECTION as " + isCollection + " on the stream, but " + fieldDef.isCollection() + " on the class's field");
          }
        }

        rtAnnSchema.addField(rtFieldSchema);
        if (isPointer)
          rtFieldSchemaToStoreIds.put(rtFieldSchema, storeId);
      } // for each field
    } // for each klass

    if (klassIdMeta == null)
      throw new ReaderException("Did not read in a __meta__ class");
    final RTAnnSchema rtDocSchema = rt.getSchema(klassIdMeta);
    rt.setDocSchema(rtDocSchema);

    // Read the stores header.
    // <stores> ::= [ <store> ]
    final int nstores = unpacker.unpackArrayHeader();
    for (int n = 0; n != nstores; n++) {
      // <store> ::= ( <store_name>, <klass_id>, <store_nelem> )
      final int ntriple = unpacker.unpackArrayHeader();
      if (ntriple != 3)
        throw new ReaderException("Invalid sized tuple read in: expected 3 elements but found " + ntriple);
      final String storeName = unpacker.unpackString();
      final int klassId = unpacker.unpackInt();
      final int nElem = unpacker.unpackInt();

      // Sanity check on the value of the klassId.
      if (klassId >= rt.getSchemas().size())
        throw new ReaderException("klassId value " + klassId + " >= number of klasses (" + rt.getSchemas().size() + ")");

      // Lookup the store on the Doc class.
      StoreSchema def = null;
      for (StoreSchema store : docSchema.getStores()) {
        if (store.getSerial().equals(storeName)) {
          def = store;
          break;
        }
      }

      final RTAnnSchema klass = rt.getSchema(klassId);
      RTStoreSchema rtStoreSchema;
      if (def == null)
        rtStoreSchema = new RTStoreSchema(n, storeName, klass, null, nElem);
      else
        rtStoreSchema = new RTStoreSchema(n, storeName, klass, def);
      rtDocSchema.addStore(rtStoreSchema);

      // Ensure that the stream store and the static store agree on the klass they're storing.
      if (!rtStoreSchema.isLazy()) {
        final Class<? extends Ann> storeKlass = def.getStoredKlass();
        final Class<? extends Ann> klassKlass = klass.getDef().getKlass();
        if (!storeKlass.equals(klassKlass))
          throw new ReaderException("Store '" + storeName + "' points to " + storeKlass + " but the stream says it points to " + klassKlass);

        // Resize the store to house the correct number of instances.
        def.resize(nElem, doc);
      }
    }  // for each store.


    // Back-fill each of the pointer fields to point to the actual RTStoreSchema instance.
    for (RTAnnSchema rtSchema : rt.getSchemas()) {
      for (RTFieldSchema rtField : rtSchema.getFields()) {
        if (!rtFieldSchemaToStoreIds.containsKey(rtField))
          continue;

        // Sanity check on the value of store_id.
        final int storeId = rtFieldSchemaToStoreIds.get(rtField);
        if (storeId >= rtDocSchema.getStores().size())
          throw new ReaderException("storeId value " + storeId + " >= number of stores (" + rtDocSchema.getStores().size()+ ")");
        final RTStoreSchema rtStore = rtDocSchema.getStore(storeId);

        // If the field isn't lazy, ensure the field and the store point to the same type.
        if (!rtField.isLazy()) {
          final Class<?> pointedToKlass = rtField.getDef().getPointedToKlass();
          final Class<?> storedKlass = rtStore.getDef().getStoredKlass();
          if (pointedToKlass != storedKlass)
            throw new ReaderException("Field points to " + pointedToKlass + " but the containing Store stores " + storedKlass);
        }

        // Update the field pointer value.
        rtField.setContainingStore(rtStore);
      }
    }


    // Read the document instance.
    // <doc_instance> ::= <instances_nbytes> <instance>
    do {
      final long _instancesNBytes = unpacker.unpackLong();
      if (_instancesNBytes > Integer.MAX_VALUE)
        throw new ReaderException("<instances_nbytes> is too large for Java (" + _instancesNBytes + ")");
      final int instancesNBytes = (int) _instancesNBytes;

      // Read all of the doc's fields lazily, if required.
      if (!docSchema.hasFields()) {
        byte[] lazyBytes = new byte[instancesNBytes];
        final int nbytesRead = in.read(lazyBytes);
        if (nbytesRead != instancesNBytes)
          throw new ReaderException("Failed to read in " + instancesNBytes + " from the input stream");

        // Attach the lazy fields to the doc.
        rtDocSchema.setLazy(lazyBytes);
        break;
      }

      final ByteArrayOutputStream lazyBOS = new ByteArrayOutputStream(instancesNBytes);
      final MessagePacker lazyPacker = MessagePackFactory.newDefaultPacker(lazyBOS);
      int lazyNElem = 0;

      // <instance> ::= { <field_id> : <obj_val> }
      final int nitems = unpacker.unpackMapHeader();
      for (int i = 0; i != nitems; i++) {
        final int key = unpacker.unpackInt();
        final RTFieldSchema field = rtDocSchema.getField(key);

        // deserialize the field value if required
        if (field.isLazy()) {
          final ValueHolder lazyValue = new ValueHolder();
          unpacker.unpackValue(lazyValue);
          lazyPacker.packInt(key);
          lazyPacker.packValue(lazyValue.get());
          lazyNElem++;
        }
        else {
          in.mark(0);
          final int availableBefore = in.available();
          ReaderHelper.read(field, doc, doc, null, unpacker);
          final int availableAfter = in.available();

          // Keep a lazy serialized copy of the field if required.
          if (field.getDef().getMode() == FieldMode.READ_ONLY) {
            in.reset();
            final int lazyNBytes = availableBefore - availableAfter;
            final byte[] lazyData = new byte[lazyNBytes];
            final int nRead = in.read(lazyData);
            if (nRead != lazyNBytes)
              throw new ReaderException("Failed to read the correct number of bytes");
            lazyPacker.packInt(key);
            lazyPacker.flush();
            lazyBOS.write(lazyData);
            lazyNElem++;
          }
        }
      }  // for each field.

      // Store the lazy slab on the doc if it was used.
      if (lazyNElem != 0) {
        lazyPacker.flush();
        doc.setDRLazy(lazyBOS.toByteArray());
        doc.setDRLazyNElem(lazyNElem);
      }
    } while (false);


    // Read the store instances.
    // <instances_groups> ::= <instances_group>*
    for (RTStoreSchema rtStoreSchema : rtDocSchema.getStores()) {
      // <instances_group>  ::= <instances_nbytes> <instances>
      final long _instancesNBytes = unpacker.unpackLong();
      if (_instancesNBytes > Integer.MAX_VALUE)
        throw new ReaderException("<instances_nbytes> is too large for Java (" + _instancesNBytes + ")");
      final int instancesNBytes = (int) _instancesNBytes;

      // Read the store lazily, if required.
      if (rtStoreSchema.isLazy()) {
        byte[] lazyBytes = new byte[instancesNBytes];
        final int nbytesRead = in.read(lazyBytes);
        if (nbytesRead != instancesNBytes)
          throw new ReaderException("Failed to read in " + instancesNBytes + " from the input stream");

        // Attach the lazy store to the rtStore instance.
        rtStoreSchema.setLazy(lazyBytes);
        continue;
      }

      final RTAnnSchema storedKlass = rtStoreSchema.getStoredKlass();
      final Store<? extends Ann> store = rtStoreSchema.getDef().getStore(doc);

      // <instances> ::= [ <instance> ]
      final int ninstances = unpacker.unpackArrayHeader();
      for (int o = 0; o != ninstances; o++) {
        final Ann ann = store.get(o);
        final ByteArrayOutputStream lazyBOS = new ByteArrayOutputStream();
        final MessagePacker lazyPacker = MessagePackFactory.newDefaultPacker(lazyBOS);
        int lazyNElem = 0;

        // <instance> ::= { <field_id> : <obj_val> }
        final int nitems = unpacker.unpackMapHeader();
        for (int i = 0; i != nitems; i++) {
          final int key = unpacker.unpackInt();
          final RTFieldSchema field = storedKlass.getField(key);

          // Deserialize the field value, if required.
          if (field.isLazy()) {
            final ValueHolder lazyValue = new ValueHolder();
            unpacker.unpackValue(lazyValue);
            lazyPacker.packInt(key);
            lazyPacker.packValue(lazyValue.get());
            lazyNElem++;
          }
          else {
            in.mark(0);
            final int availableBefore = in.available();
            ReaderHelper.read(field, ann, doc, store, unpacker);
            final int availableAfter = in.available();

            // Keep a lazy serialized copy of the field if required.
            if (field.getDef().getMode() == FieldMode.READ_ONLY) {
              in.reset();
              final int lazyNBytes = availableBefore - availableAfter;
              final byte[] lazyData = new byte[lazyNBytes];
              final int nRead = in.read(lazyData);
              if (nRead != lazyNBytes)
                throw new ReaderException("Failed to read the correct number of bytes");
              lazyPacker.packInt(key);
              lazyPacker.flush();
              lazyBOS.write(lazyData);
              lazyNElem++;
            }
          }
        }  // for each field.

        // If there were any lazy fields on the Ann instance, store them on the instance.
        if (lazyNElem != 0) {
          lazyPacker.flush();
          ann.setDRLazy(lazyBOS.toByteArray());
          ann.setDRLazyNElem(lazyNElem);
        }
      }  // for each instance
    }  // for each instance group
  }
}
