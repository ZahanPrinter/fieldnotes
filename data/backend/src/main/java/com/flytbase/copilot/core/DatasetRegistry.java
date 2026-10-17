package com.flytbase.copilot.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flytbase.copilot.chat.MemoryStore;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** Owns isolated, persistent SQL databases and the currently selected copilot runtime. */
@Component
public final class DatasetRegistry {
  public record DatabaseInfo(String id, String name, boolean selected, int records, String latestChange) {}
  public record Runtime(String databaseId, Dataset dataset, Overlay overlay, Domain domain, DocIndex documents, Tools tools, Agent agent) {}
  private final Path root;
  private final String sourceRoot;
  private final String asOf;
  private final Agent.Llm llm;
  private volatile Runtime active;
  private volatile String activeId;
  private final Map<String,HikariDataSource> pools = new ConcurrentHashMap<>();

  public DatasetRegistry(@Value("${copilot.db-root:./databases}") String dbRoot,
      @Value("${copilot.data-root:../../data/sample-data}") String sourceRoot,
      @Value("${copilot.as-of:2026-10-17}") String asOf, Agent.Llm llm) throws Exception {
    this.root = Path.of(dbRoot).toAbsolutePath().normalize(); this.sourceRoot = sourceRoot; this.asOf = asOf; this.llm = llm;
    Files.createDirectories(root);
    Path sample = root.resolve("sample");
    if (!Files.exists(sample.resolve("database.json"))) {
      Path data = Path.of(sourceRoot).toAbsolutePath().normalize();
      if (!Files.exists(data.resolve("fleet/areas.json"))) data = Path.of("../sample-data").toAbsolutePath().normalize();
      createDatabase("sample", "Sample field", data);
    }
    this.activeId = readSelected(); activate(activeId);
  }

  public Runtime current() { return active; }
  public synchronized List<DatabaseInfo> list() throws IOException {
    List<DatabaseInfo> out = new ArrayList<>();
    try (var paths = Files.list(root)) {
      for (Path p : paths.filter(x -> Files.isDirectory(x) && Files.exists(x.resolve("database.json"))).toList()) {
        JsonNode meta = Dataset.M.readTree(p.resolve("database.json").toFile());
        int count = sqlCount(p.getFileName().toString());
        String latest = latestChange(p.getFileName().toString());
        out.add(new DatabaseInfo(p.getFileName().toString(), meta.path("name").asText(p.getFileName().toString()), p.getFileName().toString().equals(activeId), count, latest));
      }
    }
    out.sort(Comparator.comparing(DatabaseInfo::name, String.CASE_INSENSITIVE_ORDER)); return out;
  }

  public synchronized DatabaseInfo importZip(String filename, MultipartFile file) throws Exception {
    if (file == null || file.isEmpty()) throw new IllegalArgumentException("Choose a non-empty ZIP file.");
    if (!filename.toLowerCase(Locale.ROOT).endsWith(".zip")) throw new IllegalArgumentException("Import a .zip file containing the field dataset.");
    if (file.getSize() > 100L * 1024 * 1024) throw new IllegalArgumentException("ZIP files must be 100 MB or smaller.");
    String id = "db-" + UUID.randomUUID().toString().substring(0, 8);
    Path staging = root.resolve(".import-" + id).normalize(); Files.createDirectories(staging);
    try {
      long total = 0; int entries = 0;
      try (ZipInputStream zip = new ZipInputStream(file.getInputStream())) {
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
          if (++entries > 10000) throw new IllegalArgumentException("ZIP contains too many files.");
          Path dest = staging.resolve(entry.getName()).normalize();
          if (!dest.startsWith(staging) || entry.getName().startsWith("/") || entry.getName().contains("\\")) throw new IllegalArgumentException("ZIP contains an unsafe file path.");
          if (entry.isDirectory()) Files.createDirectories(dest);
          else {
            Files.createDirectories(dest.getParent());
            try (OutputStream out = Files.newOutputStream(dest)) {
              byte[] buffer = new byte[8192]; int n;
              while ((n = zip.read(buffer)) != -1) { total += n; if (total > 500L * 1024 * 1024) throw new IllegalArgumentException("Expanded ZIP exceeds 500 MB."); out.write(buffer, 0, n); }
            }
          }
          zip.closeEntry();
        }
      }
      Path datasetRoot = locateRoot(staging);
      Dataset parsed = Dataset.load(datasetRoot);
      if (!hasStructuredFiles(datasetRoot)) throw new IllegalArgumentException("ZIP does not contain any JSON or JSONL data files.");
      String name = filename.replaceFirst("(?i)\\.zip$", "").replaceAll("[_-]+", " ").trim();
      createDatabase(id, name, datasetRoot);
      deleteTree(staging);
      activeId = id; activate(id); Files.writeString(root.resolve("selected-database"), id);
      return list().stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
    } catch (Exception e) { deleteTree(staging); deleteTree(root.resolve(id)); throw e; }
  }

  public synchronized void select(String id) throws Exception {
    Path dir = root.resolve(id).normalize(); if (!dir.startsWith(root) || !Files.exists(dir.resolve("database.json"))) throw new IllegalArgumentException("Database not found.");
    activate(id); activeId = id; Files.writeString(root.resolve("selected-database"), id);
  }

  public synchronized List<Map<String,Object>> history() throws Exception {
    List<Map<String,Object>> rows = new ArrayList<>();
    try (Connection c = connection(activeId); PreparedStatement ps = c.prepareStatement("SELECT change_id,entity_type,record_id,change_type,changed_at,summary,changed_by,reason,record_version,before_json,after_json FROM change_history WHERE change_type<>'import' OR entity_type='database' ORDER BY change_id DESC LIMIT 50"); ResultSet rs = ps.executeQuery()) {
      while (rs.next()) { Map<String,Object> row=new LinkedHashMap<>();String editorId=rs.getString(7);Models.User editor=editorId==null?null:active.dataset().users.get(editorId);row.put("changeId",rs.getLong(1));row.put("entityType",rs.getString(2));row.put("recordId",rs.getString(3));row.put("changeType",rs.getString(4));row.put("changedAt",rs.getString(5));row.put("summary",rs.getString(6));row.put("changedBy",editor==null?editorId:editor.name());row.put("changedById",editorId);row.put("reason",rs.getString(8));row.put("version",rs.getObject(9));row.put("oldValue",jsonOrNull(rs.getString(10)));row.put("newValue",jsonOrNull(rs.getString(11)));rows.add(row); }
    } return rows;
  }

  public synchronized Map<String,Object> records(String id,String type,String query,int limit,int offset) throws SQLException,IOException {
    requireDatabase(id);String entity=normalizeType(type);int page=Math.max(1,Math.min(limit,100)),skip=Math.max(0,offset);String q=query==null?"":query.trim();
    String sql="SELECT record_id,well_id,event_date,record_version,record_json FROM records WHERE entity_type=? AND (?='' OR LOWER(record_id) LIKE ? OR LOWER(well_id) LIKE ? OR LOWER(record_json) LIKE ?) ORDER BY event_date DESC,record_id LIMIT ? OFFSET ?";
    List<Map<String,Object>> out=new ArrayList<>();
    try(Connection c=connection(id);PreparedStatement p=c.prepareStatement(sql)){String term="%"+q.toLowerCase(Locale.ROOT)+"%";p.setString(1,entity);p.setString(2,q);p.setString(3,term);p.setString(4,term);p.setString(5,term);p.setInt(6,page);p.setInt(7,skip);try(ResultSet rs=p.executeQuery()){while(rs.next()){Map<String,Object> row=new LinkedHashMap<>();row.put("id",rs.getString(1));row.put("wellId",rs.getString(2));row.put("date",rs.getString(3));row.put("version",rs.getInt(4));row.put("record",Dataset.M.readTree(rs.getString(5)));out.add(row);}}}
    return Map.of("entityType",entity,"query",q,"limit",page,"offset",skip,"records",out);
  }
  public synchronized List<Map<String,Object>> entityTypes(String id) throws SQLException {
    requireDatabase(id);List<Map<String,Object>> out=new ArrayList<>();
    try(Connection c=connection(id);Statement s=c.createStatement();ResultSet rs=s.executeQuery("SELECT entity_type,COUNT(*) FROM records GROUP BY entity_type ORDER BY entity_type")){
      while(rs.next()){String type=rs.getString(1);out.add(Map.of("type",type,"label",typeLabel(type),"count",rs.getInt(2)));}
    } return out;
  }

  public synchronized Map<String,Object> record(String id,String type,String recordId)throws Exception{
    requireDatabase(id);String entity=normalizeType(type);try(Connection c=connection(id);PreparedStatement p=c.prepareStatement("SELECT well_id,event_date,record_version,record_json FROM records WHERE entity_type=? AND record_id=?")){p.setString(1,entity);p.setString(2,recordId);try(ResultSet rs=p.executeQuery()){if(!rs.next())throw new IllegalArgumentException("Record not found.");return Map.of("entityType",entity,"id",recordId,"wellId",rs.getString(1)==null?"":rs.getString(1),"date",rs.getString(2)==null?"":rs.getString(2),"version",rs.getInt(3),"record",Dataset.M.readTree(rs.getString(4)));}}
  }

  public synchronized Map<String,Object> editRecord(String id,String type,String recordId,int expectedVersion,JsonNode changes,String author,String reason) throws Exception {
    requireDatabase(id);String entity=normalizeType(type);try(Connection check=connection(id);PreparedStatement p=check.prepareStatement("SELECT COUNT(*) FROM records WHERE entity_type='user' AND record_id=?")){p.setString(1,author);try(ResultSet rs=p.executeQuery()){if(!rs.next()||rs.getInt(1)==0)throw new IllegalArgumentException("Choose a valid user as the editor.");}}
    if(reason==null||reason.trim().length()<3||reason.length()>2000)throw new IllegalArgumentException("Add a reason of 3 to 2,000 characters for this change.");
    if(changes==null||!changes.isObject()||changes.isEmpty())throw new IllegalArgumentException("Provide a JSON object with the fields you want to change.");
    try(Connection c=connection(id)){
      c.setAutoCommit(false);
      try(PreparedStatement read=c.prepareStatement("SELECT record_json,record_version FROM records WHERE entity_type=? AND record_id=? FOR UPDATE")){
        read.setString(1,entity);read.setString(2,recordId);
        try(ResultSet rs=read.executeQuery()){
          if(!rs.next())throw new IllegalArgumentException("Record not found.");
          int version=rs.getInt(2);if(version!=expectedVersion)throw new RecordConflictException("This record changed since you opened it. Reload the latest version before saving.");
          JsonNode before=Dataset.M.readTree(rs.getString(1));JsonNode after=before.deepCopy();List<Map<String,Object>> fields=new ArrayList<>();
          Iterator<Map.Entry<String,JsonNode>> it=changes.fields();
          while(it.hasNext()){
            var entry=it.next();String field=entry.getKey();
            if(field.isBlank()||field.length()>100||field.endsWith("_id")||field.equals("asset_id")||!before.has(field))throw new IllegalArgumentException("Field '"+field+"' is not editable on this record.");
            JsonNode oldValue=before.get(field),newValue=entry.getValue();if(newValue==null||newValue.isNull())throw new IllegalArgumentException("Field '"+field+"' cannot be set to null.");
            if(oldValue!=null&&oldValue.getNodeType()!=newValue.getNodeType()&&!(oldValue.isNumber()&&newValue.isNumber()))throw new IllegalArgumentException("Field '"+field+"' must keep its current value type ("+oldValue.getNodeType().name().toLowerCase(Locale.ROOT)+").");
            if(!oldValue.equals(newValue)){((com.fasterxml.jackson.databind.node.ObjectNode)after).set(field,newValue);fields.add(Map.of("field",field,"oldValue",oldValue,"newValue",newValue));}
          }
          if(fields.isEmpty())throw new IllegalArgumentException("No values changed; enter at least one different value.");
          String afterJson=Dataset.M.writeValueAsString(after);int next=version+1;String now=java.time.Instant.now().toString();
          if(entity.equals("inspection")) for(Map<String,Object> changed:fields){String overlayField=correctionField((String)changed.get("field"));if(overlayField!=null)try(PreparedStatement supersede=c.prepareStatement("UPDATE corrections SET is_active=FALSE WHERE inspection_id=? AND field=? AND is_active=TRUE")){supersede.setString(1,recordId);supersede.setString(2,overlayField);supersede.executeUpdate();}}
          try(PreparedStatement update=c.prepareStatement("UPDATE records SET record_json=?,record_version=?,event_date=? WHERE entity_type=? AND record_id=? AND record_version=?")){
            update.setString(1,afterJson);update.setInt(2,next);update.setString(3,recordDate(after));update.setString(4,entity);update.setString(5,recordId);update.setInt(6,version);if(update.executeUpdate()!=1)throw new RecordConflictException("This record was updated by another user. Reload and review the latest version.");
          }
          String summary="Updated "+fields.stream().map(x->x.get("field").toString()).reduce((a,b)->a+", "+b).orElse("record");
          try(PreparedStatement audit=c.prepareStatement("INSERT INTO change_history(entity_type,record_id,change_type,changed_at,summary,changed_by,reason,record_version,before_json,after_json) VALUES(?,?,'edit',?,?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS)){
            audit.setString(1,entity);audit.setString(2,recordId);audit.setString(3,now);audit.setString(4,summary);audit.setString(5,author);audit.setString(6,reason.trim());audit.setInt(7,next);audit.setString(8,Dataset.M.writeValueAsString(before));audit.setString(9,afterJson);audit.executeUpdate();
            long changeId=0;try(ResultSet keys=audit.getGeneratedKeys()){if(keys.next())changeId=keys.getLong(1);}c.commit();
            if(activeId.equals(id))activate(id);
            Map<String,Object> result=new LinkedHashMap<>();result.put("changeId",changeId);result.put("entityType",entity);result.put("recordId",recordId);result.put("version",next);result.put("changedBy",editorName(id,author));result.put("changedById",author);result.put("reason",reason.trim());result.put("changedAt",now);result.put("changedFields",fields);result.put("oldValue",before);result.put("newValue",after);return result;
          }
        }
      }catch(Exception e){c.rollback();throw e;}finally{c.setAutoCommit(true);}
    }
  }

  public static final class RecordConflictException extends RuntimeException { public RecordConflictException(String message){super(message);} }

  private void requireDatabase(String id){Path dir=root.resolve(id).normalize();if(id==null||!dir.startsWith(root)||!Files.exists(dir.resolve("database.json")))throw new IllegalArgumentException("Database not found.");}
  private static String normalizeType(String type){String t=type==null?"":type.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+","_").replaceAll("_+","_").replaceAll("^_|_$","");if(t.endsWith("ies")&&t.length()>3)t=t.substring(0,t.length()-3)+"y";else if(t.endsWith("s")&&!t.endsWith("ss"))t=t.substring(0,t.length()-1);if(!t.matches("[a-z][a-z0-9_]{0,79}"))throw new IllegalArgumentException("Choose a valid record type.");return t;}
  private static String typeLabel(String type){return Arrays.stream(type.split("_")).filter(x->!x.isBlank()).map(x->Character.toUpperCase(x.charAt(0))+x.substring(1)).reduce((a,b)->a+" "+b).orElse(type);}
  private static Object jsonOrNull(String value)throws IOException{return value==null?null:Dataset.M.readTree(value);}
  private static String recordDate(JsonNode record){for(String k:List.of("inspected_at","started_at","date","created_at")){if(record.hasNonNull(k))return record.get(k).asText();}return "";}
  private static String correctionField(String field){return switch(field){case "wellhead_temp_c"->"WELLHEAD_TEMP_C";case "ambient_temp_c"->"AMBIENT_TEMP_C";case "spill_est_volume_bbl"->"SPILL_VOLUME_BBL";default->null;};}
  private String editorName(String id,String editorId)throws SQLException,IOException{try(Connection c=connection(id);PreparedStatement p=c.prepareStatement("SELECT record_json FROM records WHERE entity_type='user' AND record_id=?")){p.setString(1,editorId);try(ResultSet rs=p.executeQuery()){return rs.next()?Dataset.M.readTree(rs.getString(1)).path("name").asText(editorId):editorId;}}}

  private void activate(String id) throws Exception {
    ensureSchema(id);
    JsonNode meta = Dataset.M.readTree(root.resolve(id).resolve("database.json").toFile());
    Dataset ds = Dataset.load(Path.of(meta.path("dataPath").asText()).toAbsolutePath().normalize());
    ds.clearStructuredRecords();
    try(Connection c=connection(id);Statement s=c.createStatement();ResultSet rs=s.executeQuery("SELECT entity_type,record_json FROM records ORDER BY entity_type,record_id")) {
      while(rs.next()) ds.addSqlRecord(rs.getString(1),rs.getString(2));
    }
    ds.finishSqlLoad();
    Overlay ov = new Overlay(); loadCorrections(id, ov); ov.setCorrectionSink(x -> { try { saveCorrection(id,x); } catch (SQLException e) { throw new IllegalStateException("Could not persist correction history",e); } }); Domain domain = new Domain(ds, ov); DocIndex docs = new DocIndex(ds);
    Tools tools = new Tools(domain, docs, LocalDate.parse(asOf));
    active = new Runtime(id, ds, ov, domain, docs, tools, new Agent(llm, tools, ds));
  }

  /** Apply additive schema upgrades whenever an existing database is opened, before reading it. */
  private void ensureSchema(String id) throws SQLException {
    try(Connection c=connection(id);Statement s=c.createStatement()){
      s.execute("CREATE TABLE IF NOT EXISTS records(entity_type VARCHAR(80) NOT NULL, record_id VARCHAR(200) NOT NULL, well_id VARCHAR(100), event_date VARCHAR(40), record_json CLOB NOT NULL, record_version INT NOT NULL DEFAULT 1, PRIMARY KEY(entity_type,record_id))");
      s.execute("ALTER TABLE records ADD COLUMN IF NOT EXISTS record_version INT NOT NULL DEFAULT 1");
      s.execute("CREATE TABLE IF NOT EXISTS change_history(change_id BIGINT AUTO_INCREMENT PRIMARY KEY, entity_type VARCHAR(80), record_id VARCHAR(200), change_type VARCHAR(40), changed_at VARCHAR(40), summary VARCHAR(1000), changed_by VARCHAR(200), reason CLOB, record_version INT, before_json CLOB, after_json CLOB)");
      s.execute("ALTER TABLE change_history ADD COLUMN IF NOT EXISTS changed_by VARCHAR(200)");
      s.execute("ALTER TABLE change_history ADD COLUMN IF NOT EXISTS reason CLOB");
      s.execute("ALTER TABLE change_history ADD COLUMN IF NOT EXISTS record_version INT");
      s.execute("CREATE TABLE IF NOT EXISTS metadata(meta_key VARCHAR(100) PRIMARY KEY, meta_value VARCHAR(1000))");
      s.execute("CREATE TABLE IF NOT EXISTS corrections(correction_id VARCHAR(100) PRIMARY KEY, inspection_id VARCHAR(200), field VARCHAR(100), original_value VARCHAR(500), new_value VARCHAR(500), author_id VARCHAR(200), reason CLOB, changed_at VARCHAR(40), source VARCHAR(100), is_active BOOLEAN NOT NULL DEFAULT TRUE)");
      s.execute("ALTER TABLE corrections ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT TRUE");
      s.execute("CREATE INDEX IF NOT EXISTS idx_records_well_date ON records(well_id,event_date)");
      s.execute("CREATE TABLE IF NOT EXISTS chat_conversations(user_id VARCHAR(200) NOT NULL, conversation_id VARCHAR(200) NOT NULL, title VARCHAR(300), created_at VARCHAR(40), updated_at VARCHAR(40), PRIMARY KEY(user_id,conversation_id))");
      s.execute("CREATE TABLE IF NOT EXISTS chat_messages(user_id VARCHAR(200) NOT NULL, conversation_id VARCHAR(200) NOT NULL, sequence INT NOT NULL, role VARCHAR(20) NOT NULL, content CLOB, payload_json CLOB, message_json CLOB, created_at VARCHAR(40), PRIMARY KEY(user_id,conversation_id,sequence))");
      s.execute("CREATE INDEX IF NOT EXISTS idx_chat_messages_user_conversation ON chat_messages(user_id,conversation_id,sequence)");
    }
  }

  public synchronized List<JsonNode> conversationHistory(String id,String userId,String conversationId)throws SQLException,IOException{
    List<JsonNode> rows=new ArrayList<>();
    try(Connection c=connection(id);PreparedStatement p=c.prepareStatement("SELECT role,content FROM chat_messages WHERE user_id=? AND conversation_id=? ORDER BY sequence DESC LIMIT 14")){
      p.setString(1,userId);p.setString(2,conversationId);try(ResultSet rs=p.executeQuery()){while(rs.next())rows.add(Dataset.M.createObjectNode().put("role",rs.getString(1)).put("content",rs.getString(2)==null?"":rs.getString(2)));}
    } Collections.reverse(rows);return rows;
  }

  public synchronized List<Map<String,Object>> conversations(String id,String userId)throws SQLException,IOException{
    List<Map<String,Object>> out=new ArrayList<>();Map<String,Map<String,Object>> indexed=new LinkedHashMap<>();
    String sql="SELECT c.conversation_id,c.title,c.created_at,c.updated_at,m.role,m.content,m.payload_json,m.message_json FROM chat_conversations c LEFT JOIN chat_messages m ON m.user_id=c.user_id AND m.conversation_id=c.conversation_id WHERE c.user_id=? ORDER BY c.updated_at DESC,m.sequence";
    try(Connection c=connection(id);PreparedStatement p=c.prepareStatement(sql)){p.setString(1,userId);try(ResultSet rs=p.executeQuery()){while(rs.next()){
      String conversationId=rs.getString(1);Map<String,Object> row=indexed.get(conversationId);
      if(row==null){row=new LinkedHashMap<>();row.put("id",conversationId);row.put("title",rs.getString(2));row.put("createdAt",rs.getString(3));row.put("updatedAt",rs.getString(4));row.put("databaseId",id);row.put("messages",new ArrayList<Map<String,Object>>());indexed.put(conversationId,row);}
      String role=rs.getString(5);if(role!=null){Object message=jsonOrNull(rs.getString(8));if(message instanceof JsonNode node&&node.isObject())((List<Object>)row.get("messages")).add(Dataset.M.convertValue(node,Map.class));else if("user".equals(role))((List<Object>)row.get("messages")).add(Map.of("role","user","text",rs.getString(6)==null?"":rs.getString(6)));else {Object payload=jsonOrNull(rs.getString(7));((List<Object>)row.get("messages")).add(Map.of("role","assistant","payload",payload==null?Map.of("answer",rs.getString(6)==null?"":rs.getString(6)):payload));}}
    }}}
    out.addAll(indexed.values());return out;
  }

  public synchronized void saveChatTurn(String id,String userId,String conversationId,String question,Agent.Answer answer)throws Exception{
    String now=java.time.Instant.now().toString();
    try(Connection c=connection(id)){
      c.setAutoCommit(false);
      try{
        String title=question.trim().replaceAll("\\s+"," ");if(title.length()>42)title=title.substring(0,39)+"…";
        boolean existing=false;try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM chat_conversations WHERE user_id=? AND conversation_id=?")){p.setString(1,userId);p.setString(2,conversationId);try(ResultSet rs=p.executeQuery()){existing=rs.next();}}
        if(existing){try(PreparedStatement p=c.prepareStatement("UPDATE chat_conversations SET updated_at=? WHERE user_id=? AND conversation_id=?")){p.setString(1,now);p.setString(2,userId);p.setString(3,conversationId);p.executeUpdate();}}
        else try(PreparedStatement p=c.prepareStatement("INSERT INTO chat_conversations(user_id,conversation_id,title,created_at,updated_at) VALUES(?,?,?,?,?)")){p.setString(1,userId);p.setString(2,conversationId);p.setString(3,title);p.setString(4,now);p.setString(5,now);p.executeUpdate();}
        int sequence=0;try(PreparedStatement p=c.prepareStatement("SELECT COALESCE(MAX(sequence),0) FROM chat_messages WHERE user_id=? AND conversation_id=?")){p.setString(1,userId);p.setString(2,conversationId);try(ResultSet rs=p.executeQuery()){if(rs.next())sequence=rs.getInt(1);}}
        ObjectNode user=Dataset.M.createObjectNode().put("role","user").put("text",question);
        ObjectNode payload=Dataset.M.valueToTree(answer);ObjectNode assistant=Dataset.M.createObjectNode().put("role","assistant");assistant.set("payload",payload);
        try(PreparedStatement p=c.prepareStatement("INSERT INTO chat_messages(user_id,conversation_id,sequence,role,content,payload_json,message_json,created_at) VALUES(?,?,?,?,?,?,?,?)")){
          p.setString(1,userId);p.setString(2,conversationId);p.setInt(3,sequence+1);p.setString(4,"user");p.setString(5,question);p.setNull(6,Types.CLOB);p.setString(7,Dataset.M.writeValueAsString(user));p.setString(8,now);p.addBatch();
          p.setString(1,userId);p.setString(2,conversationId);p.setInt(3,sequence+2);p.setString(4,"assistant");p.setString(5,answer.text());p.setString(6,Dataset.M.writeValueAsString(payload));p.setString(7,Dataset.M.writeValueAsString(assistant));p.setString(8,now);p.addBatch();p.executeBatch();
        }
        try(PreparedStatement p=c.prepareStatement("UPDATE chat_conversations SET updated_at=? WHERE user_id=? AND conversation_id=?")){p.setString(1,now);p.setString(2,userId);p.setString(3,conversationId);p.executeUpdate();}
        c.commit();
      }catch(Exception e){c.rollback();throw e;}finally{c.setAutoCommit(true);}
    }
  }

  public synchronized void syncConversations(String id,String userId,List<JsonNode> chats)throws Exception{
    if(chats==null)return;
    for(JsonNode chat:chats){String conversationId=chat.path("id").asText("");if(conversationId.isBlank()||conversationId.length()>200)continue;
      try(Connection c=connection(id);PreparedStatement exists=c.prepareStatement("SELECT 1 FROM chat_conversations WHERE user_id=? AND conversation_id=?")){
        exists.setString(1,userId);exists.setString(2,conversationId);try(ResultSet rs=exists.executeQuery()){if(rs.next())continue;}
        String now=chat.path("updatedAt").asText(java.time.Instant.now().toString());String title=chat.path("title").asText("Conversation");
        try(PreparedStatement p=c.prepareStatement("INSERT INTO chat_conversations(user_id,conversation_id,title,created_at,updated_at) VALUES(?,?,?,?,?)")){p.setString(1,userId);p.setString(2,conversationId);p.setString(3,title);p.setString(4,chat.path("createdAt").asText(now));p.setString(5,now);p.executeUpdate();}
        JsonNode messages=chat.path("messages");if(messages.isArray())try(PreparedStatement p=c.prepareStatement("INSERT INTO chat_messages(user_id,conversation_id,sequence,role,content,payload_json,message_json,created_at) VALUES(?,?,?,?,?,?,?,?)")){int n=0;for(JsonNode message:messages){String role=message.path("role").asText("");if(!role.equals("user")&&!role.equals("assistant"))continue;JsonNode payload=message.path("payload");String content=role.equals("user")?message.path("text").asText(""):payload.path("answer").asText(message.path("error").asText(""));p.setString(1,userId);p.setString(2,conversationId);p.setInt(3,++n);p.setString(4,role);p.setString(5,content);p.setString(6,payload.isMissingNode()?null:payload.toString());p.setString(7,message.toString());p.setString(8,now);p.addBatch();}p.executeBatch();}
      }
    }
  }

  public synchronized void deleteConversation(String id,String userId,String conversationId)throws SQLException{
    try(Connection c=connection(id)){c.setAutoCommit(false);try(PreparedStatement messages=c.prepareStatement("DELETE FROM chat_messages WHERE user_id=? AND conversation_id=?");PreparedStatement conversation=c.prepareStatement("DELETE FROM chat_conversations WHERE user_id=? AND conversation_id=?")){
      messages.setString(1,userId);messages.setString(2,conversationId);messages.executeUpdate();conversation.setString(1,userId);conversation.setString(2,conversationId);if(conversation.executeUpdate()==0)throw new IllegalArgumentException("Conversation not found.");c.commit();
    }catch(Exception e){c.rollback();if(e instanceof SQLException sql)throw sql;if(e instanceof IllegalArgumentException arg)throw arg;throw new SQLException(e);}finally{c.setAutoCommit(true);}}
  }

  private void createDatabase(String id, String name, Path data) throws Exception {
    Path dir = root.resolve(id); Files.createDirectories(dir);
    Path durableData = dir.resolve("source");
    if (!data.toAbsolutePath().normalize().equals(durableData.toAbsolutePath().normalize())) copyTree(data, durableData);
    Dataset ds = Dataset.load(durableData);
    Dataset.M.writeValue(dir.resolve("database.json").toFile(), Map.of("id",id,"name",name,"createdAt",java.time.Instant.now().toString(),"dataPath",durableData.toString()));
    try (Connection c = connection(id); Statement s = c.createStatement()) {
      s.execute("CREATE TABLE IF NOT EXISTS records(entity_type VARCHAR(80) NOT NULL, record_id VARCHAR(200) NOT NULL, well_id VARCHAR(100), event_date VARCHAR(40), record_json CLOB NOT NULL, record_version INT NOT NULL DEFAULT 1, PRIMARY KEY(entity_type,record_id))");
      s.execute("CREATE INDEX IF NOT EXISTS idx_records_well_date ON records(well_id,event_date)");
      s.execute("CREATE TABLE IF NOT EXISTS change_history(change_id BIGINT AUTO_INCREMENT PRIMARY KEY, entity_type VARCHAR(80), record_id VARCHAR(200), change_type VARCHAR(40), changed_at VARCHAR(40), summary VARCHAR(1000), changed_by VARCHAR(200), reason CLOB, record_version INT, before_json CLOB, after_json CLOB)");
      s.execute("ALTER TABLE records ADD COLUMN IF NOT EXISTS record_version INT NOT NULL DEFAULT 1");
      s.execute("ALTER TABLE change_history ADD COLUMN IF NOT EXISTS changed_by VARCHAR(200)");
      s.execute("ALTER TABLE change_history ADD COLUMN IF NOT EXISTS reason CLOB");
      s.execute("ALTER TABLE change_history ADD COLUMN IF NOT EXISTS record_version INT");
      s.execute("CREATE TABLE IF NOT EXISTS metadata(meta_key VARCHAR(100) PRIMARY KEY, meta_value VARCHAR(1000))");
      s.execute("CREATE TABLE IF NOT EXISTS corrections(correction_id VARCHAR(100) PRIMARY KEY, inspection_id VARCHAR(200), field VARCHAR(100), original_value VARCHAR(500), new_value VARCHAR(500), author_id VARCHAR(200), reason CLOB, changed_at VARCHAR(40), source VARCHAR(100), is_active BOOLEAN NOT NULL DEFAULT TRUE)");
      s.execute("ALTER TABLE corrections ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT TRUE");
    }
    if(ds.users.isEmpty())ds.users.put("USER-LOCAL",new Models.User("USER-LOCAL","Field Operator","operator",""));
    persistRecords(id, ds, durableData);
  }

  private void persistRecords(String id, Dataset d, Path source) throws Exception {
    try (Connection c=connection(id); PreparedStatement p=c.prepareStatement("MERGE INTO records(entity_type,record_id,well_id,event_date,record_json) KEY(entity_type,record_id) VALUES(?,?,?,?,?)")) {
      c.setAutoCommit(false);
      for (var e:d.wells.entrySet()) if(e.getKey()!=null)add(p,"well",e.getKey(),e.getKey(),"",e.getValue());
      for (var e:d.inspections.entrySet()) for(var x:e.getValue()) if(x.inspection_id()!=null)add(p,"inspection",x.inspection_id(),e.getKey(),x.inspected_at()==null?"":x.inspected_at().toString(),x);
      for (var e:d.maintenance.entrySet()) for(var x:e.getValue()) if(x.maintenance_id()!=null)add(p,"maintenance",x.maintenance_id(),e.getKey(),x.started_at()==null?"":x.started_at().toString(),x);
      for (var e:d.regEvents.entrySet()) for(var x:e.getValue()) if(x.event_id()!=null)add(p,"regulatory_event",x.event_id(),e.getKey(),x.date()==null?"":x.date(),x);
      for (var e:d.notes.entrySet()) for(var x:e.getValue()) if(x.note_id()!=null)add(p,"note",x.note_id(),e.getKey(),x.created_at()==null?"":x.created_at().toString(),x);
      for (var e:d.users.entrySet()) if(e.getKey()!=null)add(p,"user",e.getKey(),"","",e.getValue());
      for (var e:d.areaNames.entrySet()) add(p,"area",e.getKey(),"","",Map.of("area_id",e.getKey(),"name",e.getValue()));
      persistAdditional(p,source);
      p.executeBatch();
      c.commit();
    }
    try(Connection c=connection(id);PreparedStatement h=c.prepareStatement("INSERT INTO change_history(entity_type,record_id,change_type,changed_at,summary,changed_by,reason) VALUES('database',?,'import',?,'Imported dataset into SQL','system','Initial dataset import')")) { h.setString(1,id);h.setString(2,java.time.Instant.now().toString());h.executeUpdate(); }
  }
  private void persistAdditional(PreparedStatement p,Path source)throws Exception{
    if(!Files.isDirectory(source))return;
    try(var walk=Files.walk(source)){
      for(Path file:walk.filter(Files::isRegularFile).filter(f->{String n=f.getFileName().toString().toLowerCase(Locale.ROOT);return n.endsWith(".json")||n.endsWith(".jsonl");}).toList()){
        String type=entityTypeFrom(file,source);int rowNumber=0;
        if(file.toString().toLowerCase(Locale.ROOT).endsWith(".jsonl")){
          for(String line:Files.readAllLines(file))if(!line.isBlank())try{JsonNode n=Dataset.M.readTree(line);persistGenericRow(p,type,n,file,++rowNumber);}catch(Exception ignored){rowNumber++;}
        }else try{
          JsonNode root=Dataset.M.readTree(file.toFile());JsonNode rows=root.isArray()?root:null;
          if(rows==null&&root.isObject())for(var fields=root.fields();fields.hasNext();){JsonNode value=fields.next().getValue();if(value.isArray()){rows=value;break;}}
          if(rows!=null)for(JsonNode n:rows)persistGenericRow(p,type,n,file,++rowNumber);
          else if(root!=null&&root.isObject())persistGenericRow(p,type,root,file,++rowNumber);
        }catch(Exception ignored){}
      }
    }
  }
  private static String entityTypeFrom(Path file,Path source){
    Path relative=source.relativize(file);String base=file.getFileName().toString().replaceFirst("(?i)\\.jsonl?$","").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","_").replaceAll("^_|_$","");
    if(base.equals("metadata"))return "well";
    if(base.equals("inspections"))return "inspection";
    if(base.equals("maintenance"))return "maintenance";
    if(base.equals("regulatory_events"))return "regulatory_event";
    if(base.equals("context"))return "note";
    if(base.equals("areas"))return "area";
    if(base.equals("users"))return "user";
    if(base.equals("metadata"))base="asset_metadata";
    if(base.endsWith("ies"))base=base.substring(0,base.length()-3)+"y";else if(base.endsWith("s")&&!base.endsWith("ss"))base=base.substring(0,base.length()-1);
    if(!base.matches("[a-z][a-z0-9_]{0,79}"))base="imported_record";
    return base;
  }
  private static void persistGenericRow(PreparedStatement p,String type,JsonNode row,Path file,int number)throws Exception{
    if(row==null||!row.isObject())return;String id="";
    for(String key:List.of("record_id","id","event_id","inspection_id","maintenance_id","note_id","asset_id","well_id","area_id"))if(row.hasNonNull(key)){id=row.get(key).asText();break;}
    if(id.isBlank())for(var fields=row.fields();fields.hasNext();){var e=fields.next();if(e.getKey().endsWith("_id")&&e.getValue().isValueNode()&&!e.getValue().isNull()){id=e.getValue().asText();break;}}
    if(id.isBlank())id=file.getFileName()+"-"+number;
    String well=row.path("well_id").asText(row.path("asset_id").asText(""));String date="";
    for(String key:List.of("date","event_date","created_at","inspected_at","started_at"))if(row.hasNonNull(key)){date=row.get(key).asText();break;}
    add(p,type,id,well,date,row);
  }
  private static void add(PreparedStatement p,String type,String id,String well,String date,Object row)throws Exception { p.setString(1,type);p.setString(2,id);p.setString(3,well);p.setString(4,date);p.setString(5,Dataset.M.writeValueAsString(row));p.addBatch(); }
  public synchronized void saveCorrection(String databaseId, Overlay.Correction x) throws SQLException {
    try(Connection c=connection(databaseId);PreparedStatement p=c.prepareStatement("INSERT INTO corrections(correction_id,inspection_id,field,original_value,new_value,author_id,reason,changed_at,source) VALUES(?,?,?,?,?,?,?,?,?)")) {
      p.setString(1,x.id());p.setString(2,x.inspectionId());p.setString(3,x.field().name());p.setString(4,x.originalValue());p.setString(5,x.newValue());p.setString(6,x.authorUserId());p.setString(7,x.reason());p.setString(8,x.at().toString());p.setString(9,x.source());p.executeUpdate();
      try(PreparedStatement h=c.prepareStatement("INSERT INTO change_history(entity_type,record_id,change_type,changed_at,summary,changed_by,reason,before_json,after_json) VALUES('inspection',?,'correction',?,?,?,?,?,?)")) {
        h.setString(1,x.inspectionId());h.setString(2,x.at().toString());h.setString(3,x.field()+" corrected from "+x.originalValue()+" to "+x.newValue());h.setString(4,x.authorUserId());h.setString(5,x.reason());h.setString(6,Dataset.M.getNodeFactory().textNode(x.originalValue()).toString());h.setString(7,Dataset.M.getNodeFactory().textNode(x.newValue()).toString());h.executeUpdate();
      }
    }
  }
  private void loadCorrections(String id, Overlay overlay) throws SQLException {
    try(Connection c=connection(id);Statement s=c.createStatement();ResultSet rs=s.executeQuery("SELECT correction_id,inspection_id,field,original_value,new_value,author_id,reason,changed_at,source FROM corrections WHERE is_active=TRUE ORDER BY changed_at")) {
      while(rs.next()) overlay.add(new Overlay.Correction(rs.getString(1),rs.getString(2),Overlay.Field.valueOf(rs.getString(3)),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),java.time.Instant.parse(rs.getString(8)),rs.getString(9)));
    }
  }
  private int sqlCount(String id) { try(Connection c=connection(id);Statement s=c.createStatement();ResultSet rs=s.executeQuery("SELECT COUNT(*) FROM records")){return rs.next()?rs.getInt(1):0;}catch(Exception e){return 0;} }
  private String latestChange(String id) { try(Connection c=connection(id);Statement s=c.createStatement();ResultSet rs=s.executeQuery("SELECT changed_at || ' · ' || summary FROM change_history WHERE change_type<>'import' OR entity_type='database' ORDER BY change_id DESC LIMIT 1")){return rs.next()?rs.getString(1):"";}catch(Exception e){return "";} }
  private Connection connection(String id)throws SQLException {
    try { return pools.computeIfAbsent(id,key->{ HikariConfig cfg=new HikariConfig();cfg.setJdbcUrl("jdbc:h2:file:"+root.resolve(key).resolve("fieldnotes").toString().replace('\\','/')+";ACCESS_MODE_DATA=rw;DB_CLOSE_ON_EXIT=FALSE");cfg.setUsername("sa");cfg.setPassword("");cfg.setMaximumPoolSize(3);cfg.setMinimumIdle(1);cfg.setPoolName("fieldnotes-"+key);return new HikariDataSource(cfg); }).getConnection(); }
    catch(RuntimeException e) { if(e.getCause() instanceof SQLException sql) throw sql; throw e; }
  }
  private String readSelected()throws IOException { Path p=root.resolve("selected-database");return Files.exists(p)?Files.readString(p).trim():"sample"; }
  private static boolean hasStructuredFiles(Path root)throws IOException{try(var walk=Files.walk(root)){return walk.anyMatch(p->Files.isRegularFile(p)&&(p.toString().toLowerCase(Locale.ROOT).endsWith(".json")||p.toString().toLowerCase(Locale.ROOT).endsWith(".jsonl")));}}
  private static Path locateRoot(Path stage)throws IOException {
    try(var walk=Files.walk(stage,8)){
      List<Path> files=walk.filter(Files::isRegularFile).filter(p->{String n=p.toString().toLowerCase(Locale.ROOT);return n.endsWith(".json")||n.endsWith(".jsonl");}).toList();
      if(files.isEmpty())throw new IllegalArgumentException("ZIP does not contain any JSON or JSONL data files.");
      List<Path> roots=new ArrayList<>();
      for(Path f:files){Path parent=f.getParent();while(parent!=null&&parent.startsWith(stage)){if(Files.isDirectory(parent.resolve("assets"))||Files.isDirectory(parent.resolve("fleet"))){roots.add(parent);break;}if(parent.equals(stage))break;parent=parent.getParent();}}
      if(!roots.isEmpty())return roots.stream().min(Comparator.comparingInt(Path::getNameCount)).orElseThrow();
      return files.stream().map(Path::getParent).min(Comparator.comparingInt(Path::getNameCount)).orElse(stage);
    }
  }
  private static void copyTree(Path from,Path to)throws IOException { Files.createDirectories(to);try(var walk=Files.walk(from)){for(Path p:walk.toList()){Path dest=to.resolve(from.relativize(p).toString());if(Files.isDirectory(p))Files.createDirectories(dest);else{Files.createDirectories(dest.getParent());Files.copy(p,dest,StandardCopyOption.REPLACE_EXISTING);}}} }
  private static void deleteTree(Path path) { if(path==null||!Files.exists(path))return;try(var walk=Files.walk(path)){for(Path p:walk.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}catch(IOException ignored){} }
}
