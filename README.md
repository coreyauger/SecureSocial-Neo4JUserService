SecureSocial-Neo4JUserService
=============================

Neo4J user service plugin for secure social (play framework/scala)

## Background
Building reactive web applications with playframework and scala often starts with a social login system.  One great way to get started here is to use [Secure Social](http://securesocial.ws/).  They provide a simple way to get up and running with a large number of providers.  Once you are running you will need to store your account information and more the likely the relationships between your social users "friends".  This is where Neo4J really shines.

## Requirements 
* Play Framework [play](http://www.playframework.com/)
* Secure Social plugin for play [Secure Social](http://securesocial.ws/)

## Setup
Once you have secure social [up and running](http://securesocial.ws/guide/getting-started.html) all you need to do is add this scala file to your play project.  I created a app/servicies directory to place the file.  Next you simply need to add a line to your play.plugins

play.plugins
```
9998:service.Neo4JUserService
```

## Neo4J Structure
Users will now be added to your neo4J with the following structure

```
(u:User)-[:HAS_ACCOUNT]->(p:Provider)
```

You can also use the utility methods outlined below to make users friends
```
(u1:User)-[:FRIEND]->(u2:User)
```



## Helper methods

Here are number of usefull methods for helping you to work with secure social and Neo4J

```scala
object  Neo4JUserService{
  def socialUserFromMap( umap: Map[String, String]): Identity = {
    ...
  }

  def makeUsersFriends( uuid1: String, uuid2: String ) = future{
    ...
  }

  def socialUserFromToken(provider: String, token: String) = future{
    ...
  }

  def uuidFromProviderInfo( provider: String, id: String, name: String = "" ) = future {
    ...
  }


  private def createUserAndProvider(name: String, id: String, fullname: String) = {
    ...
  }
}

```

## Things to consider.

You will still need a way for users to "link" multiple providers on your backend.  Currently if a user signs in using another provider, they will get another user and provider record in Neo4J.  You could try to combat this at the login level by looking for emails that are same as other providers (you would want to verify the email before linking for security reasons)

Another way would be a settings section on your site when a user is loged in, that would allow them to "link" their other accounts.  In this manor you would want to create to continue to build a structure like the following

```   
        /[:HAS_ACCOUNT]->(p0:Provider)
(u:User)-[:HAS_ACCOUNT]->(p1:Provider)
        \[:HAS_ACCOUNT]->(p2:Provider)
```

Update: Newer Versions of Secure Social now provide a "link" method where you can handle this.
