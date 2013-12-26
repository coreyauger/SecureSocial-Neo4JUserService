package service

import java.util.UUID
import play.api.{Logger, Application}
import play.api.Play.current
import securesocial.core._
import securesocial.core.providers.Token
import securesocial.core.IdentityId
import scala.concurrent._
import play.api.libs.concurrent.Akka
import ExecutionContext.Implicits.global
import play.api.libs.ws._
import play.api.libs.json._

import org.anormcypher._

object  Neo4JUserService{
  def socialUserFromMap( umap: Map[String, String]): Identity = {
    new SocialUser(identityId=new IdentityId(userId = umap("id"),providerId = umap("name")), firstName=umap("firstName"), lastName=umap("lastName"), fullName=umap("fullName"), email=Some(umap("email")),
      avatarUrl= Some(umap("avatarUrl")), authMethod= new AuthenticationMethod(umap("authMethod")), oAuth1Info = Some(new OAuth1Info(umap("oAuth1InfoToken"),umap("oAuth1InfoSecret"))),
      oAuth2Info = Some(new OAuth2Info(umap("oAuth2InfoAccessToken"), Some(umap("oAuth2InfoTokenType")), Some(umap("oAuth2InfoExpiresIn").toInt), Some(umap("oAuth2InfoRefreshToken")))),
      passwordInfo = Some(new PasswordInfo(umap("hasher"),umap("password"),Some(umap("salt")) )))
  }

  def makeUsersFriends( uuid1: String, uuid2: String ) = future{
    val q =
      """
        MATCH (u1:User {uuid : '%s'}), (u2:User {uuid : '%s'})
        CREATE UNIQUE (u1)-[:FRIEND]->
                       (u2)-[:FRIEND]->u1
      """.format(uuid1, uuid2)
    Cypher(q).execute
  }

  def socialUserFromToken(provider: String, token: String) = future{
    val q = "MATCH (u:User)-[:HAS_ACCOUNT]->(p:Provider) WHERE p.name = '%s' AND p.oAuth2InfoAccessToken = '%s' return p;".format(provider, token)
    Console.println("findByEmailAndProvider Query: %s".format(q))
    val node = Cypher(q).apply().head
    val umap = node[org.anormcypher.NeoNode]("p").props.map( _ match{ case (k,v) => k -> v.toString} )
    socialUserFromMap(umap)
  }

  def uuidFromProviderInfo( provider: String, id: String, name: String = "" ) = future {
    val q = "MATCH (u:User)-[:HAS_ACCOUNT]->(p:Provider) WHERE p.id = '%s' AND p.name = '%s' RETURN u.uuid as uuid;".format(id,provider)
    val stream = Cypher(q).apply()
    if( stream.isEmpty ){
      createUserAndProvider(provider, id, name)
    }else{
      //println("uuidFromProviderInfo: %s".format(stream.head[String]("uuid")))
      stream.head[String]("uuid")
    }
  }


  private def createUserAndProvider(name: String, id: String, fullname: String) = {
    val uuid = UUID.randomUUID
    future {
      val ret = Cypher("""
        CREATE (p:Provider
                {
                  name: "%s",
                  id : "%s",
                  firstName : "",
                  lastName : "",
                  fullName : "%s",
                  authMethod: "",
                  oAuth1InfoToken : "",
                  oAuth1InfoSecret : "",
                  oAuth2InfoAccessToken : "",
                  oAuth2InfoExpiresIn : 0,
                  oAuth2InfoRefreshToken : "",
                  oAuth2InfoTokenType : "",
                  avatarUrl : "",
                  email : "",
                  hasher : "",
                  password : "",
                  salt : ""
                }
              )<-[r:HAS_ACCOUNT]-(u:User
                {
                  uuid : "%s"
                }
              );
      """.format(
        name,
        id,
        fullname,
        uuid.toString)).execute
    }
    uuid.toString
  }
}


class Neo4JUserService(application: Application) extends UserServicePlugin(application) {
  private var tokens = Map[String, Token]()

  Neo4jREST.setServer("127.0.0.1", 7474, "/db/data/")

  def find(id: IdentityId): Option[Identity] = {
    if ( Logger.isDebugEnabled ) {
      Logger.debug("find user = %s".format(id))
    }
    val q = "MATCH (u:User)-[:HAS_ACCOUNT]->(p:Provider) WHERE p.id = '%s' AND p.name = '%s' return p;".format(id.userId,id.providerId)
    val node = Cypher(q).apply().head
    val umap = node[org.anormcypher.NeoNode]("p").props.map( _ match{ case (k,v) => k -> v.toString} )
    Some(Neo4JUserService.socialUserFromMap(umap))
  }

  def findByEmailAndProvider(email: String, providerId: String): Option[Identity] = {
    if ( Logger.isDebugEnabled ) {
      Logger.debug("fine user email = %s".format(email))
    }
    val q = "MATCH (u:User)-[:HAS_ACCOUNT]->(p:Provider) WHERE p.email = '%s' AND p.name = '%s' return p;".format(email, providerId)
    val node = Cypher(q).apply().head
    val umap = node[org.anormcypher.NeoNode]("p").props.map( _ match{ case (k,v) => k -> v.toString} )
    Some(Neo4JUserService.socialUserFromMap(umap))
  }

  def save(user: Identity): Identity = {
    val q = "MATCH (u:User)-[:HAS_ACCOUNT]->(p:Provider) WHERE p.name = '%s' AND p.id = '%s' return p;".format(user.identityId.providerId,user.identityId.userId)
    val stream = Cypher(q).apply()

    val oauth2: OAuth2Info = user.oAuth2Info.getOrElse(new OAuth2Info(""))
    val pinfo : PasswordInfo =  user.passwordInfo.getOrElse( new PasswordInfo("","",Some("")) )
    if( stream.isEmpty ){
      println("CREATE USER")
      val uuid = UUID.randomUUID()
      val query: String = """
        CREATE (p:Provider
        {
          name: "%s",
          id : "%s",
          firstName : "%s",
          lastName : "%s",
          fullName : "%s",
          authMethod: "%s",
          oAuth1InfoToken : "%s",
          oAuth1InfoSecret : "%s",
          oAuth2InfoAccessToken : "%s",
          oAuth2InfoExpiresIn : %s,
          oAuth2InfoRefreshToken : "%s",
          oAuth2InfoTokenType : "%s",
          avatarUrl : "%s",
          email : "%s",
          hasher : "%s",
          password : "%s",
          salt : "%s"
        }
      )<-[r:HAS_ACCOUNT]-(u:User
        {
          uuid : "%s"
        }
      );
                               """.format(
          user.identityId.providerId,
          user.identityId.userId,
          user.firstName,
          user.lastName,
          user.fullName,
          user.authMethod,
          user.oAuth1Info.getOrElse(new OAuth1Info("","")).token,
          user.oAuth1Info.getOrElse(new OAuth1Info("","")).secret,
          oauth2.accessToken,
          oauth2.expiresIn.getOrElse(0),
          oauth2.refreshToken,
          oauth2.tokenType,
          user.avatarUrl.getOrElse(""),
          user.email.getOrElse(""),
          pinfo.hasher,
          pinfo.password,
          pinfo.salt.getOrElse(""),
          uuid.toString
        )
      val res: Boolean = Cypher(query).execute()

    }else{
      // we have a provider... but need to update the tokens
      val query: String = """
                MATCH (p:Provider { name: '%s', id: '%s' })
                SET
                    p.firstName = '%s',
                    p.lastName = "%s",
                    p.fullName = "%s",
                    p.authMethod = "%s",
                    p.oAuth1InfoToken = "%s",
                    p.oAuth1InfoSecret = "%s",
                    p.oAuth2InfoAccessToken = "%s",
                    p.oAuth2InfoExpiresIn = %s,
                    p.oAuth2InfoRefreshToken = "%s",
                    p.oAuth2InfoTokenType = "%s",
                    p.avatarUrl = "%s",
                    p.email = "%s",
                    p.hasher = "%s",
                    p.password = "%s",
                    p.salt = "%s"
                RETURN p;
              """.format(
            user.identityId.providerId,
            user.identityId.userId,
            user.firstName,
            user.lastName,
            user.fullName,
            user.authMethod,
            user.oAuth1Info.getOrElse(new OAuth1Info("","")).token,
            user.oAuth1Info.getOrElse(new OAuth1Info("","")).secret,
            oauth2.accessToken,
            oauth2.expiresIn.getOrElse(0),
            oauth2.refreshToken,
            oauth2.tokenType,
            user.avatarUrl.getOrElse(""),
            user.email.getOrElse(""),
            pinfo.hasher,
            pinfo.password,
            pinfo.salt.getOrElse(""))
        val res: Boolean = Cypher(query).execute()
        Console.println("Update User: " + res)
    }



    // this sample returns the same user object, but you could return an instance of your own class
    // here as long as it implements the Identity trait. This will allow you to use your own class in the protected
    // actions and event callbacks. The same goes for the find(id: UserId) method.
    user
  }

  def save(token: Token) {
    Console.println("Token: %s".format(token))
    tokens += (token.uuid -> token)
  }

  def findToken(token: String): Option[Token] = {
    Console.println("Find Token: %s".format(token))
    tokens.get(token)
  }

  def deleteToken(uuid: String) {
    tokens -= uuid
  }

  def deleteTokens() {
    tokens = Map()
  }

  def deleteExpiredTokens() {
    tokens = tokens.filter(!_._2.isExpired)
  }
}
