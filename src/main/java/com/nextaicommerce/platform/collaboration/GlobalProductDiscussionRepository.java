package com.nextaicommerce.platform.collaboration;

import java.util.*;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Separate platform realm. No tenant review/message/attachment table is queried here. */
@Repository
public class GlobalProductDiscussionRepository {
 public record Review(UUID id,String subjectType,String subjectKey,String subjectLabel,String title,String status,
   String requester,String participants,Instant createdAt,Instant updatedAt,long messageCount,String parentUrl){}
 public record Conversation(Review review,List<CollaborationRepository.Message> messages,String messageType,List<UUID> unreadMentionMessageIds){}
 private final JdbcTemplate jdbc;
 public GlobalProductDiscussionRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
 private UUID context(String email){
  if(email==null||email.isBlank())throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  jdbc.queryForObject("SELECT set_config('app.user_email',?,true)",String.class,email);
  var ids=jdbc.query("SELECT u.id FROM app_users u JOIN platform_administrators a ON a.user_id=u.id AND a.active WHERE lower(u.email)=lower(?) AND u.status='ACTIVE'",(rs,n)->rs.getObject(1,UUID.class),email);
  if(ids.size()!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN);return ids.getFirst();
 }
 private static final String VIEW="""
  SELECT d.id,d.global_product_id,g.canonical_name,d.title,d.status,coalesce(u.display_name,u.email) requester,d.created_at,d.updated_at,
   (SELECT count(*) FROM platform_product_discussion_messages m WHERE m.discussion_id=d.id) message_count,
   coalesce((SELECT string_agg(DISTINCT coalesce(a.display_name,a.email),', ') FROM platform_product_discussion_messages m
     JOIN app_users a ON a.id=m.author_id WHERE m.discussion_id=d.id),coalesce(u.display_name,u.email)) participants
  FROM platform_product_discussions d JOIN global_catalog_products g ON g.id=d.global_product_id JOIN app_users u ON u.id=d.created_by
  """;
 private static Review review(java.sql.ResultSet rs,int row)throws java.sql.SQLException{
  return new Review(rs.getObject("id",UUID.class),"GLOBAL_PRODUCT",rs.getString("global_product_id"),rs.getString("canonical_name"),rs.getString("title"),rs.getString("status"),rs.getString("requester"),rs.getString("participants"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant(),rs.getLong("message_count"),"/app/platform/catalog");
 }
 private Review required(UUID id){return required(id,false);}
 private Review required(UUID id,boolean lock){var rows=jdbc.query(VIEW+" WHERE d.id=?"+(lock?" FOR UPDATE OF d":""),GlobalProductDiscussionRepository::review,id);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);return rows.getFirst();}
 @Transactional(readOnly=true) public List<Conversation> subject(String email,UUID product){
  context(email);return jdbc.query(VIEW+" WHERE d.global_product_id=? ORDER BY d.updated_at DESC,d.id",GlobalProductDiscussionRepository::review,product).stream().map(r->new Conversation(r,List.of(),"SUMMARY",List.of())).toList();
 }
 @Transactional(readOnly=true) public Conversation conversation(String email,UUID id){
  context(email);var review=required(id);
  var attachments=jdbc.query("SELECT a.id,a.message_id,a.file_name,a.content_type,octet_length(a.file_bytes) size,a.created_at FROM platform_product_discussion_attachments a JOIN platform_product_discussion_messages m ON m.id=a.message_id WHERE m.discussion_id=? ORDER BY a.created_at,a.id",(rs,n)->Map.entry(rs.getObject("message_id",UUID.class),new CollaborationRepository.Attachment(rs.getObject("id",UUID.class),rs.getString("file_name"),rs.getString("content_type"),rs.getLong("size"),rs.getTimestamp("created_at").toInstant())),id);
  var byMessage=new HashMap<UUID,List<CollaborationRepository.Attachment>>();for(var a:attachments)byMessage.computeIfAbsent(a.getKey(),k->new ArrayList<>()).add(a.getValue());
  var messages=jdbc.query("SELECT m.id,m.author_id,m.body,m.created_at,u.display_name,u.email FROM platform_product_discussion_messages m JOIN app_users u ON u.id=m.author_id WHERE m.discussion_id=? ORDER BY m.created_at,m.id",(rs,n)->new CollaborationRepository.Message(rs.getObject("id",UUID.class),rs.getObject("author_id",UUID.class),rs.getString("display_name"),rs.getString("email"),"TEAM_CHAT",rs.getString("body"),rs.getTimestamp("created_at").toInstant(),byMessage.getOrDefault(rs.getObject("id",UUID.class),List.of())),id);
  return new Conversation(review,messages,"TEAM_CHAT",List.of());
 }
 @Transactional(readOnly=true) public Map<String,Map<String,Long>> summaries(String email,List<UUID> products){
  context(email);if(products.size()>250)throw new IllegalArgumentException("Request up to 250 product summaries.");if(products.isEmpty())return Map.of();
  var result=new LinkedHashMap<String,Map<String,Long>>();jdbc.query("SELECT d.global_product_id,count(*) total,count(*) FILTER(WHERE d.status='ACTIVE') active,count(*) FILTER(WHERE d.status='CLOSED') closed,(SELECT count(*) FROM platform_product_discussion_messages m JOIN platform_product_discussions x ON x.id=m.discussion_id WHERE x.global_product_id=d.global_product_id) messages FROM platform_product_discussions d WHERE d.global_product_id IN ("+String.join(",",Collections.nCopies(products.size(),"?"))+") GROUP BY d.global_product_id",rs->{long active=rs.getLong("active");result.put(rs.getString("global_product_id"),Map.of("activeCount",active,"totalCount",rs.getLong("total"),"closedCount",rs.getLong("closed"),"originCount",active,"directMessageCount",rs.getLong("messages"),"relatedCount",0L,"relatedMessageCount",0L));},products.toArray());return result;
 }
 @Transactional(readOnly=true) public List<CollaborationRepository.Member> members(String email){
  context(email);return jdbc.query("SELECT u.id,u.display_name,u.email FROM app_users u JOIN platform_administrators a ON a.user_id=u.id AND a.active WHERE u.status='ACTIVE' ORDER BY lower(u.display_name),u.id",(rs,n)->new CollaborationRepository.Member(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),"admin."+rs.getObject(1,UUID.class).toString().substring(0,8)));
 }
 @Transactional(readOnly=true) public CollaborationRepository.AttachmentData attachment(String email,UUID id){
  context(email);var rows=jdbc.query("SELECT a.file_name,a.content_type,a.file_bytes FROM platform_product_discussion_attachments a JOIN platform_product_discussion_messages m ON m.id=a.message_id JOIN platform_product_discussions d ON d.id=m.discussion_id WHERE a.id=?",(rs,n)->new CollaborationRepository.AttachmentData(rs.getString(1),rs.getString(2),rs.getBytes(3)),id);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);return rows.getFirst();
 }
 private static String body(String body,List<CollaborationRepository.AttachmentUpload> files){
  String value=body==null?"":body.trim();if(value.isEmpty()&&!files.isEmpty())value="Shared an attachment.";
  if(value.isEmpty()||value.length()>4000)throw new IllegalArgumentException("Write a message of up to 4000 characters or attach a file.");return value;
 }
 private void message(UUID discussion,UUID actor,String text,List<CollaborationRepository.AttachmentUpload> files){
  UUID id=jdbc.queryForObject("INSERT INTO platform_product_discussion_messages(discussion_id,author_id,body) VALUES(?,?,?) RETURNING id",UUID.class,discussion,actor,body(text,files));
  for(var file:files)jdbc.update("INSERT INTO platform_product_discussion_attachments(message_id,file_name,content_type,file_bytes) VALUES(?,?,?,?)",id,file.fileName(),file.contentType(),file.bytes());
 }
 @Transactional public UUID create(String email,UUID product,String text,List<CollaborationRepository.AttachmentUpload> files){
  UUID actor=context(email);body(text,files);var names=jdbc.query("SELECT canonical_name FROM global_catalog_products WHERE id=?",(rs,n)->rs.getString(1),product);if(names.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  String title="Discussion about "+names.getFirst();if(title.length()>240)title=title.substring(0,240);
  UUID id=jdbc.queryForObject("INSERT INTO platform_product_discussions(global_product_id,title,created_by) VALUES(?,?,?) RETURNING id",UUID.class,product,title,actor);message(id,actor,text,files);return id;
 }
 @Transactional public UUID reply(String email,UUID id,String text,String status,List<CollaborationRepository.AttachmentUpload> files){
  UUID actor=context(email);var row=required(id,true);if(!row.status().equals("ACTIVE"))throw new IllegalArgumentException("Reopen this discussion before replying.");
  if(status!=null&&!status.equals("CLOSED"))throw new IllegalArgumentException("Choose a supported discussion action.");
  if((text!=null&&!text.isBlank())||!files.isEmpty())message(id,actor,text,files);else if(status==null)throw new IllegalArgumentException("Write a message or attach a file.");
  if("CLOSED".equals(status))jdbc.update("UPDATE platform_product_discussions SET status='CLOSED',closed_by=?,closed_at=now(),updated_at=now() WHERE id=?",actor,id);else jdbc.update("UPDATE platform_product_discussions SET updated_at=now() WHERE id=?",id);return id;
 }
 @Transactional public UUID reopen(String email,UUID id){context(email);required(id,true);jdbc.update("UPDATE platform_product_discussions SET status='ACTIVE',closed_by=NULL,closed_at=NULL,updated_at=now() WHERE id=?",id);return id;}
}
