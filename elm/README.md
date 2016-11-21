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
    
### Api endpoints
    
#### `/wallet`
This is main endpoint used to interact with application, it allows user to check wallet balance and make payments.

- `GET /wallet/address` - returns address for wallet associated with given node that can be use to perform payments

- `GET /wallet/funds` - returns amount of funds that is associated with wallet associated with given node

- `GET /wallet/payment` - performs payment to specified `address`, payment requires to specify positive values for `amount` (coins that are transferred to address) and `fee` (coins for node that will forge block) 

- `GET /wallet/coinage` - returns amount of coinage that is associated with wallet associated with given node that can be used for mining

#### `/blocktree`
This endpoint is for getting information about blocktree, it allows quering specific blockchains form blocktree

- `GET /blocktree/mainchain` - returns main chain blocks (ids only)

- `GET /blocktree/blocks/chain/{n}` - returns chain by score, where n = 1 is chain with best score, 2 is second best and so on (block with transactions in json format)

- `GET /blocktree/leaves` -  return leafs of blocktree (ids only)

- `GET /blocktree/chain/{n}` -  returns chain by score, where n = 1 is chain with best score, 2 is second best and so on (ids only)

- `GET /blocktree/block/{id}` - returns block form block tree by given id (block with transactions in json format)

#### `/peers`
This endpoint is for getting information about peers that participates in network

- `GET /peers/connected` - return list of peers connected to given node 

- `GET /peers/blacklisted` - returns blacklisted pears

- `POST /peers/connect` - connects to specific node by ip address and port, example content of request: `{"host":"127.0.0.1", "port":"9084"}` 

- `GET /peers/all` - return list of all known pears

### Configuration
Configuration is encoded using HOCON (Human-Optimized Config Object Notation), since valid JSON is also valid HOCON, `elm` configuration is added next to existing `Scorex` configuration.

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
    
- `consensus` configuration defining parameters of blocktree and coinage spent on blocks
    
    `N` - max number of branches in blocktree
    
    `confirmation-depth` - unused for now
    
    `base-target` - it is target score of coinage that should be spent when forging new block, currently it's constant, but nodes can choose to spend more
    
    example:
    ```
    consensus {
        N = 8
        confirmation-depth = 1
        base-target = 100
    }
    ```
    
- `forging` configuration defining forging new blocks
    
    `delay` - delay between 2 consecutive block forging attempts
    
    `strategy` - name of strategy that should be used for forging new block currently there are 2 available: `simple-forging-strategy` and `dumb-forging-strategy`. 
    Depending on selected `strategy` there can be further configuration. 
    
    example:
    ```
    forging {
        delay = 10s
        strategy = "simple-forging-strategy"
        simple-forging-strategy {
          target-ratio = 1.0
          min-transactions = 1
          max-transactions = 100
        }
      }
    ```    
    
    
    
    
    
    
# 5. Testing
    TODO
    
# 6. Conclusions
    TODO
