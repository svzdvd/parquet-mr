/**
 * Copyright 2012 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package parquet.format.converter;

import static parquet.format.Util.readFileMetaData;
import static parquet.format.Util.writePageHeader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import parquet.Log;
import parquet.format.ColumnChunk;
import parquet.format.DataPageHeader;
import parquet.format.DictionaryPageHeader;
import parquet.format.Encoding;
import parquet.format.FieldRepetitionType;
import parquet.format.FileMetaData;
import parquet.format.KeyValue;
import parquet.format.PageHeader;
import parquet.format.PageType;
import parquet.format.RowGroup;
import parquet.format.SchemaElement;
import parquet.format.Type;
import parquet.hadoop.metadata.BlockMetaData;
import parquet.hadoop.metadata.ColumnChunkMetaData;
import parquet.hadoop.metadata.CompressionCodecName;
import parquet.hadoop.metadata.ParquetMetadata;
import parquet.io.ParquetDecodingException;
import parquet.schema.GroupType;
import parquet.schema.MessageType;
import parquet.schema.PrimitiveType;
import parquet.schema.PrimitiveType.PrimitiveTypeName;
import parquet.schema.Type.Repetition;
import parquet.schema.TypeVisitor;

public class ParquetMetadataConverter {
  private static final Log LOG = Log.getLog(ParquetMetadataConverter.class);

  public FileMetaData toParquetMetadata(int currentVersion, ParquetMetadata parquetMetadata) {
    List<BlockMetaData> blocks = parquetMetadata.getBlocks();
    List<RowGroup> rowGroups = new ArrayList<RowGroup>();
    int numRows = 0;
    for (BlockMetaData block : blocks) {
      numRows += block.getRowCount();
      addRowGroup(parquetMetadata, rowGroups, block);
    }
    FileMetaData fileMetaData = new FileMetaData(
        currentVersion,
        toParquetSchema(parquetMetadata.getFileMetaData().getSchema()),
        numRows,
        rowGroups
        );

    Set<Entry<String, String>> keyValues = parquetMetadata.getFileMetaData().getKeyValueMetaData().entrySet();
    for (Entry<String, String> keyValue : keyValues) {
      addKeyValue(fileMetaData, keyValue.getKey(), keyValue.getValue());
    }

    fileMetaData.setCreated_by(parquetMetadata.getFileMetaData().getCreatedBy());

    return fileMetaData;
  }

  List<SchemaElement> toParquetSchema(MessageType schema) {
    List<SchemaElement> result = new ArrayList<SchemaElement>();
    addToList(result, schema);
    return result;
  }

  private void addToList(final List<SchemaElement> result, parquet.schema.Type field) {
    field.accept(new TypeVisitor() {
      @Override
      public void visit(PrimitiveType primitiveType) {
        SchemaElement element = new SchemaElement(primitiveType.getName());
        element.setRepetition_type(toParquetRepetition(primitiveType.getRepetition()));
        element.setType(getType(primitiveType.getPrimitiveTypeName()));
        result.add(element);
      }

      @Override
      public void visit(MessageType messageType) {
        SchemaElement element = new SchemaElement(messageType.getName());
        visitChildren(result, messageType.asGroupType(), element);
      }

      @Override
      public void visit(GroupType groupType) {
        SchemaElement element = new SchemaElement(groupType.getName());
        element.setRepetition_type(toParquetRepetition(groupType.getRepetition()));
        visitChildren(result, groupType, element);
      }

      private void visitChildren(final List<SchemaElement> result,
          GroupType groupType, SchemaElement element) {
        element.setNum_children(groupType.getFieldCount());
        result.add(element);
        for (parquet.schema.Type field : groupType.getFields()) {
          addToList(result, field);
        }
      }
    });
  }

  private void addRowGroup(ParquetMetadata parquetMetadata, List<RowGroup> rowGroups, BlockMetaData block) {
    //rowGroup.total_byte_size = ;
    List<ColumnChunkMetaData> columns = block.getColumns();
    List<ColumnChunk> parquetColumns = new ArrayList<ColumnChunk>();
    for (ColumnChunkMetaData columnMetaData : columns) {
      ColumnChunk columnChunk = new ColumnChunk(columnMetaData.getFirstDataPageOffset()); // verify this is the right offset
      columnChunk.file_path = block.getPath(); // they are in the same file for now
      columnChunk.meta_data = new parquet.format.ColumnMetaData(
          getType(columnMetaData.getType()),
          toFormatEncodings(columnMetaData.getEncodings()),
          Arrays.asList(columnMetaData.getPath()),
          columnMetaData.getCodec().getParquetCompressionCodec(),
          columnMetaData.getValueCount(),
          columnMetaData.getTotalUncompressedSize(),
          columnMetaData.getTotalSize(),
          columnMetaData.getFirstDataPageOffset()
          );
      columnChunk.meta_data.dictionary_page_offset = columnMetaData.getDictionaryPageOffset();
//      columnChunk.meta_data.index_page_offset = ;
//      columnChunk.meta_data.key_value_metadata = ; // nothing yet

      parquetColumns.add(columnChunk);
    }
    RowGroup rowGroup = new RowGroup(parquetColumns, block.getTotalByteSize(), block.getRowCount());
    rowGroups.add(rowGroup);
  }

  private List<Encoding> toFormatEncodings(List<parquet.column.Encoding> encodings) {
    List<Encoding> converted = new ArrayList<Encoding>();
    for (parquet.column.Encoding encoding : encodings) {
      converted.add(getEncoding(encoding));
    }
    return converted;
  }

  private static final class EncodingList {

    private final List<parquet.column.Encoding> encodings;

    public EncodingList(List<parquet.column.Encoding> encodings) {
      this.encodings = encodings;
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof EncodingList) {
        List<parquet.column.Encoding> other = ((EncodingList)obj).encodings;
        if (other.size() != encodings.size()) {
          return false;
        }
        for (int i = 0; i < other.size(); i++) {
          if (!other.get(i).equals(encodings.get(i))) {
            return false;
          }
        }
        return true;
      }
      return false;
    }

    @Override
    public int hashCode() {
      int result = 1;
      for (parquet.column.Encoding element : encodings)
        result = 31 * result + (element == null ? 0 : element.hashCode());
      return result;
    }
  }

  private Map<EncodingList, List<parquet.column.Encoding>> encodingLists = new HashMap<EncodingList, List<parquet.column.Encoding>>();

  private List<parquet.column.Encoding> fromFormatEncodings(List<Encoding> encodings) {
    List<parquet.column.Encoding> converted = new ArrayList<parquet.column.Encoding>();
    for (Encoding encoding : encodings) {
      converted.add(getEncoding(encoding));
    }
    converted = Collections.unmodifiableList(converted);
    EncodingList key = new EncodingList(converted);
    List<parquet.column.Encoding> cached = encodingLists.get(key);
    if (cached == null) {
      cached = converted;
      encodingLists.put(key, cached);
    }
    return cached;
  }

  public parquet.column.Encoding getEncoding(Encoding encoding) {
    return parquet.column.Encoding.valueOf(encoding.name());
  }

  public Encoding getEncoding(parquet.column.Encoding encoding) {
    return Encoding.valueOf(encoding.name());
  }

  PrimitiveTypeName getPrimitive(Type type) {
    switch (type) {
      case BYTE_ARRAY: // TODO: rename BINARY and remove this switch
        return PrimitiveTypeName.BINARY;
      case INT64:
        return PrimitiveTypeName.INT64;
      case INT32:
        return PrimitiveTypeName.INT32;
      case BOOLEAN:
        return PrimitiveTypeName.BOOLEAN;
      case FLOAT:
        return PrimitiveTypeName.FLOAT;
      case DOUBLE:
        return PrimitiveTypeName.DOUBLE;
      case INT96:
        return PrimitiveTypeName.INT96;
      case FIXED_LEN_BYTE_ARRAY:
        return PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY;
      default:
        throw new RuntimeException("Unknown type " + type);
    }
  }

  Type getType(PrimitiveTypeName type) {
    switch (type) {
      case INT64:
        return Type.INT64;
      case INT32:
        return Type.INT32;
      case BOOLEAN:
        return Type.BOOLEAN;
      case BINARY:
        return Type.BYTE_ARRAY;
      case FLOAT:
        return Type.FLOAT;
      case DOUBLE:
        return Type.DOUBLE;
      case INT96:
        return Type.INT96;
      case FIXED_LEN_BYTE_ARRAY:
        return Type.FIXED_LEN_BYTE_ARRAY;
      default:
        throw new RuntimeException("Unknown type " + type);
    }
  }

  private void addKeyValue(FileMetaData fileMetaData, String key, String value) {
    KeyValue keyValue = new KeyValue(key);
    keyValue.value = value;
    fileMetaData.addToKey_value_metadata(keyValue);
  }

  public ParquetMetadata readParquetMetadata(InputStream from) throws IOException {
    FileMetaData fileMetaData = readFileMetaData(from);
    if (Log.DEBUG) LOG.debug(fileMetaData);
    ParquetMetadata parquetMetadata = fromParquetMetadata(fileMetaData);
    if (Log.DEBUG) LOG.debug(ParquetMetadata.toPrettyJSON(parquetMetadata));
    return parquetMetadata;
  }

  public ParquetMetadata fromParquetMetadata(FileMetaData parquetMetadata) throws IOException {
    MessageType messageType = fromParquetSchema(parquetMetadata.getSchema());
    List<BlockMetaData> blocks = new ArrayList<BlockMetaData>();
    List<RowGroup> row_groups = parquetMetadata.getRow_groups();
    for (RowGroup rowGroup : row_groups) {
      BlockMetaData blockMetaData = new BlockMetaData();
      blockMetaData.setRowCount(rowGroup.getNum_rows());
      blockMetaData.setTotalByteSize(rowGroup.getTotal_byte_size());
      List<ColumnChunk> columns = rowGroup.getColumns();
      String filePath = columns.get(0).getFile_path();
      for (ColumnChunk columnChunk : columns) {
        if ((filePath == null && columnChunk.getFile_path() != null)
            || (filePath !=null && !filePath.equals(columnChunk.getFile_path()))) {
          throw new ParquetDecodingException("all column chunks of the same row group must be in the same file for now");
        }
        parquet.format.ColumnMetaData metaData = columnChunk.meta_data;
        String[] path = getPath(metaData);
        ColumnChunkMetaData column = new ColumnChunkMetaData(
            path,
            messageType.getType(path).asPrimitiveType().getPrimitiveTypeName(),
            CompressionCodecName.fromParquet(metaData.codec),
            fromFormatEncodings(metaData.encodings));
        column.setFirstDataPageOffset(metaData.data_page_offset);
        column.setDictionaryPageOffset(metaData.dictionary_page_offset);
        column.setValueCount(metaData.num_values);
        column.setTotalUncompressedSize(metaData.total_uncompressed_size);
        column.setTotalSize(metaData.total_compressed_size);
        // TODO
        // index_page_offset
        // key_value_metadata
        blockMetaData.addColumn(column);
      }
      blockMetaData.setPath(filePath);
      blocks.add(blockMetaData);
    }
    Map<String, String> keyValueMetaData = new HashMap<String, String>();
    List<KeyValue> key_value_metadata = parquetMetadata.getKey_value_metadata();
    if (key_value_metadata != null) {
      for (KeyValue keyValue : key_value_metadata) {
        keyValueMetaData.put(keyValue.key, keyValue.value);
      }
    }
    return new ParquetMetadata(
        new parquet.hadoop.metadata.FileMetaData(messageType, keyValueMetaData, parquetMetadata.getCreated_by()),
        blocks);
  }


  private static final class Path {

    private final String[] p;

    public Path(String[] path) {
      this.p = path;
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof Path) {
        Arrays.equals(p, ((Path)obj).p);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(p);
    }
  }

  private Map<Path, String[]> paths = new HashMap<Path, String[]>();

  private String[] getPath(parquet.format.ColumnMetaData metaData) {
    String[] path = metaData.path_in_schema.toArray(new String[metaData.path_in_schema.size()]);
    Path key = new Path(path);
    String[] cached = paths.get(path);
    if (cached == null) {
      for (int i = 0; i < path.length; i++) {
        path[i] = path[i].intern();
      }
      cached = path;
      paths.put(key, cached);
    }
    return cached;
  }

  MessageType fromParquetSchema(List<SchemaElement> schema) {

    Iterator<SchemaElement> iterator = schema.iterator();
    SchemaElement root = iterator.next();
    return new MessageType(root.getName(), convertChildren(iterator, root.getNum_children()));
  }

  private parquet.schema.Type[] convertChildren(Iterator<SchemaElement> schema, int childrenCount) {
    parquet.schema.Type[] result = new parquet.schema.Type[childrenCount];
    for (int i = 0; i < result.length; i++) {
      SchemaElement schemaElement = schema.next();
      if ((!schemaElement.isSetType() && !schemaElement.isSetNum_children())
          || (schemaElement.isSetType() && schemaElement.isSetNum_children())) {
        throw new RuntimeException("bad element " + schemaElement);
      }
      Repetition repetition = fromParquetRepetition(schemaElement.getRepetition_type());
      String name = schemaElement.getName();
      if (schemaElement.type != null) {
        result[i] = new PrimitiveType(
            repetition,
            getPrimitive(schemaElement.getType()),
            name);
      } else {
        result[i] = new GroupType(
            repetition,
            name,
            convertChildren(schema, schemaElement.getNum_children()));
      }
    }
    return result;
  }

  FieldRepetitionType toParquetRepetition(Repetition repetition) {
    return FieldRepetitionType.valueOf(repetition.name());
  }

  Repetition fromParquetRepetition(FieldRepetitionType repetition) {
    return Repetition.valueOf(repetition.name());
  }

  public void writeDataPageHeader(
      int uncompressedSize,
      int compressedSize,
      int valueCount,
      parquet.column.Encoding rlEncoding,
      parquet.column.Encoding dlEncoding,
      parquet.column.Encoding valuesEncoding,
      OutputStream to) throws IOException {
    writePageHeader(newDataPageHeader(uncompressedSize, compressedSize, valueCount, rlEncoding, dlEncoding, valuesEncoding), to);
  }

  private PageHeader newDataPageHeader(
      int uncompressedSize, int compressedSize,
      int valueCount,
      parquet.column.Encoding rlEncoding,
      parquet.column.Encoding dlEncoding,
      parquet.column.Encoding valuesEncoding) {
    PageHeader pageHeader = new PageHeader(PageType.DATA_PAGE, uncompressedSize, compressedSize);
    // TODO: pageHeader.crc = ...;
    pageHeader.data_page_header = new DataPageHeader(
        valueCount,
        getEncoding(valuesEncoding),
        getEncoding(dlEncoding),
        getEncoding(rlEncoding));
    return pageHeader;
  }

  public void writeDictionaryPageHeader(
      int uncompressedSize, int compressedSize, int valueCount,
      parquet.column.Encoding valuesEncoding, OutputStream to) throws IOException {
    PageHeader pageHeader = new PageHeader(PageType.DICTIONARY_PAGE, uncompressedSize, compressedSize);
    pageHeader.dictionary_page_header = new DictionaryPageHeader(valueCount, getEncoding(valuesEncoding));
    writePageHeader(pageHeader, to);
  }


}
