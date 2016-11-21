# 1. Overview

# 2. Implementation details

    - Peercoin
      TODO
    
    - Multi-chain
      TODO
# 3. Modifications in Scorex

    TODO - only listed files that have changes 
    
    - in `src/main/scala/scorex/core/NodeViewHolder.scala`
    - in `src/main/scala/scorex/core/api/http/CompositeHttpService.scala`
    - in `src/main/scala/scorex/core/app/Application.scala`
    - in `src/main/scala/scorex/core/network/NetworkController.scala`
    - in `src/main/scala/scorex/core/network/NodeViewSynchronizer.scala`
    - in `src/main/scala/scorex/core/network/peer/PeerDatabase.scala`
    - in `src/main/scala/scorex/core/network/peer/PeerDatabaseImpl.scala`
    - in `src/main/scala/scorex/core/network/peer/PeerManager.scala`
    - in `src/main/scala/scorex/core/transaction/Transaction.scala`
  
# 4. Running

   Application node can be run with `java -jar` command but you have to pass `-Dconfig.file=conf_file` that points to configuration file, for example `java -Dconfig.file=conf_file -jar `.
   To create executable jar with all dependencies you have to execute sbt command `sbt "project elm" assembly`, jar will be created in `elm/target` directory.
   There are 3 configuration files provided so nodes can be launched by executing following commands
   ```
   java -Dconfig.file=elm/application-1.conf -jar elm/target/elm.jar
   java -Dconfig.file=elm/application-2.conf -jar elm/target/elm.jar
   java -Dconfig.file=elm/application-3.conf -jar elm/target/elm.jar
   ```
   After that every node will start http server with swagger accessible at url as follows: 

   ```
   node1 http://localhost:9085/swagger
   node2 http://localhost:9087/swagger
   node3 http://localhost:9089/swagger
   ```
    
   In configuration it is set that node1 generates genesis block and adds to its wallet 10000 coins
    
### Configuration
`elm` is root for configuration for this project
There are 4 sections inside it:
 - `node` which has basic information about particular node
 
    `app-name` and `name` - strings used to derive name that is given to akka actor system that is created for given node.
 
    `version` - value that has to be 3 numbers separated with dots like `1.1.1` this value is passed to NetworkController and than used for handshake with other nodes
    
    `shutdown-hook` - boolean value used to indicate that after application shutdown there should be message logged (in our case).
    
    `key-pair-seed` - bas58 encoded value that is used to deterministically generate secret for wallet associated with given node
    
    example:
    ```
    node {
        app-name = "elm"
        version = "1.0.0"
        name = "local-node"
        shutdown-hook = true
        key-pair-seed = "5rcRxdD7jwGDe9XgmEodHdgo6681DVtb1wT3DocNchuR"
    }
    ```
 - `genesis` which holds configuration for creating genesis block
    
    `generate` - boolean value that indicates if genesis block should be created for given node, it should be set to `true` only for one node
    example:
    
    `initial-funds` - amount of conis that will be added to wallet in genesis transaction
    
    `grains` - size of TXOs that will be added in genesis block, for example if `initial-funds` is set to 100 and `grains` is set to 10 there will be 10 txo in genesis block everyone will have 10 coins.
    
    example:
    ```
    genesis {
        generate = false
        initial-funds = 0
        grains = 10
    }
    ```
    
# 5. Testing
    TODO
    
# 6. Conclusions
    TODO
