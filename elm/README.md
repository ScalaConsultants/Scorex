# Project Elm

Elm is a proof-of-concept cryptocurrency with the following characteristics:

- Peercoin-like Proof-of-Stake consensus protocol
- nodes store multiple best chains and continually contribute to all of them
- based on Scorex 2.0 framework

## Implementation Details

### Peercoin-like Consensus

Peercoin uses both Proof-of-Stake and Proof-of-Work systems (the latter is meant to become obsolete over time)
and a checkpointing mechanism - a form of centralized supervision. Like in Bitcoin, the transactions are made
up of series of inputs and outputs, in our code represented by `TxInput` and `TxOutput` classes. Peercoin introduces
the concept of _coin age_ which is used for minting PoS blocks - it is defined as currency amount times 
holding period. Also worth mentioning is that Peercoin has a steady unlimited inflation, and transactions fees are fixed
and destroyed when a block is incorporated in to the chain. For more information about Peercoin see its 
[white paper](https://peercoin.net/assets/paper/peercoin-paper.pdf) and [wiki](https://wiki.peercointalk.org).


#### Main Differences
Elm differs from Peercoin in the following aspects:

- only the Proof-of-Stake system is used, with no checkpointing
- coin age is calculated based on the depth of owned transaction outputs in the chain
- there is no inflation: the sum of coins is preconfigured, and nodes earn transactions fees when forging new blocks

#### Coin Age

The main motivation for using chain depth instead of actual time
is that we wanted to avoid dealing with timestamps and time synchronization. As the chain depth is relative to the time 
passed it should result in the similar effect regarding the probabilty of forging a block by a stakeholder. Technically it is realized
by `TxOutput` class having `height` property which is established during block incorporation.


#### Proof of Stake

Like in Peercoin the first transaction in a block is the so called _coinstake_ and it provides closed boxes (UTXOs)
to be used for coin age calculation. Those boxes are returned to the miner in a new `TxOuput` (thus the depth
factor of available coin age is reset) along with the sum of fees of the transactions in the forged block. Note that the 
chain depth is calculate only once when a new block is created, there is no need to reevaluate as the chain grows. 
Because fees are the only way a miner can gain from forging a block, empty blocks (without any regular transactions) are 
explicitly forbidden.
 
### Multi-chain / Blocktree

In order to store multiple chains the blocks are organized into a tree of blocks (blocktree), each branch of the
tree being a competing blockchain. Whenever two competing (trying to extend the same parent) blocks are encountered the
chain is forked into two branches. After each successful update to the blocktree, the number of branches is limited
to a configured number `N`. If the number of branches exceeds `N`, they are compared by accumulated score 
(the coin age put into the blocks), and the lowest score branches are removed.

Technically, the tree is realized as `ElmBlocktree` class that holds a `Map` from block ID to a `Node` (a class
that holds a block along with some extra data). The `Nodes` are linked via parent-children references, and the leaves
are kept as a separate set of IDs. The true root of the tree is the so called _zero node_ as defined in `Elmblocktree.zeroNode`,
but it's just a convienient implementation detail. The logical root is the genesis block/node created during startup
in `ElmNodeviewHolder` (if the configuration allows it).

#### Synchronizing Blocktrees

Synchronization was a challenge because the standard `Equal`, `Older`, `Younger` relation proposed by Scorex doesn't apply 
straight-forwardly to blocktrees. The only situation when a blocktree is `Older` is when it cointains all the leaves 
of another blocktree at some deeper level. So apart from the obvious `Equal` case, in all other situations a blocktree is
considered `Younger`, thus two blocktrees can be mutually `Younger`. In the extreme case two mutually `Younger` blocktrees
could have completely different sets of leaves.
 
This means that it is impossible to establish `startingPoints`/`extensions` without the knowledge of the other tree.
That's why the `ElmSyncInfo` class provides the set of _all_ the blocks in the tree and a set of its leaves. This of
course may become a problem as the blocktree grows, so a more efficient solution would be desired.

#### Storing State

To be able to continually contribute to parallel chains nodes need to store state associated with each of them. Additionally, 
in the process of removing branches from the tree, a chain could be rolled back to any previous parent block. Therefore we
need to store the state for all blocks in the blocktree (actually we could probably think of some optimization, like a cutoff 
at a certain depth). 

This is realized through `ElmNodeViewHolder` having a `Map` from block ID to `FullState` class, which consists of a minimal 
state (a collection UTXOs), a wallet (UTXOs owned by this node), and a memory pool (transaction which have not made it to 
the chain yet). 

Elm does not store any state or blocks persistently.


## Working with Scorex 2.0

Scorex definitely helped us create this project. We especially appreciate its networking capabilities - building a peer
network required little involvement from us. However, as at the time of this development, Scorex 2.0 was still in an early
phase, working with it came with several challenges.

### Versioning

One of those challenges was the fact that Scorex was not realeased, and it was continually improved upon into the master branch without any versioning.
That's why we created this fork. On several occasions we tried to rebase our work against the upstream, but that proved to 
be too costly. The last Scorex change that has made it into our solution has been git-tagged: `scorex-upstream-2016-11-13`

### Modifications
Also, we needed to make the several changes to the Scorex code to make our solution work, the most important ones are:

- `NodeViewHolder` - multiple methods were changed from `private` to `protected` to allow overriding in `ElmNodeViewHolder`, also
small changes to allow handling errors with `cats.data.Xor` instead of `scala.util.Try`
- `NodeViewSynchronizer` - changed to broadcast unconfirmed transactions to peers
- `Transaction` - we needed a consistent tx ID for simulation
- `PeerDatabase` (and other classes) - to be able to rerun the simulation the database had to be closed properly 
- `NetworkController` (and other classes) - to be able to run the `PeersApiRoute`

### Heavy Interfaces

We have found that many extended traits forced us to implement methods we didn't need. We have marked those methods with 
`@deprecated` annotation (see `ElmBlocktree`, `ElmMinState`, et al.)


## Using the Application

### Configuration

All configuration options are described in [reference.conf](https://github.com/ScalaConsultants/Scorex/blob/elm-develop/elm/src/main/resources/reference.conf), and those specific to the simulation - in 
[simulation-common.conf](https://github.com/ScalaConsultants/Scorex/blob/elm-develop/elm/src/test/resources/simulation-common.conf). 
Scorex settings, which are usually specified as a separate JSON file, have been incorporated into the `*.conf` files
by utilizing the fact that HOCON is a superset of JSON.
 
Several ready to use configuration files have been prepared: `elm/application-[1-3].conf`. Each defines only the most
relevant subset of settings (the missing ones are resolved from `reference.conf`)

For simulation the configuration files: `elm/src/test/resources/simulation-node[1-4].conf` are first resolved against
`simulation-common.conf` and then `reference.conf`.

### Building

To build the application run:

```
sbt "project elm" assembly
```

This will create a fat jar under `elm/target/elm.jar`.
  
### Running

To run a single node, from `elm` directory type:

```
java -Dconfig.file=path/to/config -jar target/elm.jar
```

If `-Dconfig.file` property is omitted the `reference.conf` will be used. To run a network of the 3 predefined nodes, run:

```
java -Dconfig.file=application-1.conf -jar target/elm.jar
java -Dconfig.file=application-2.conf -jar target/elm.jar
java -Dconfig.file=application-3.conf -jar target/elm.jar
```

After that each node will start a HTTP server with Swagger accessible at following URLs: 

```
node1 http://localhost:9085/swagger
node2 http://localhost:9087/swagger
node3 http://localhost:9089/swagger
```
    
### Simulation

To test the application, we created a simulation which runs multiple nodes from within the test. To run it, type:

```
sbt "project elm" test
```

The nodes are defined in configuration files `elm/src/test/resources/simulation-node[1-4].conf`. The simulation will try 
to make a configured number of random transactions between those nodes, and assert whether the balances of each wallet
check out.

For debugging it may a good idea to adjust `elm/src/test/resources/logback-test.xml`.
    
### HTTP API Endpoints

Two `ApiRoutes` have been added: `WalletApiRoute` and `BlocktreeApiRoute`. They defined a number endpoints 
for interacting with wallets and blocktrees. The most useful are: 

- `GET /wallet/address` - returns the address (base58 encoded public key) for wallet associated with given node. 
It can be used to make payments to that node.

- `GET /wallet/payment` - performs payment by specifying `address`, `amount` and `fee`, it will return an ID of the 
newly made transaction.

- `GET /blocktree/blocks/chain/{n}` - returns a chain (blocks with transactions in JSON format) by score, where n = 1 is 
the main chain, 2 is second best and so on.


## Conclusions

### Challenges
Working on this project has been both inspiring and challenging. The main challenges included:

- understanding the domain: cryptocurrencies, blockchains, PoW/PoS schemes
- debugging: it's always hard to debug highly concurrent applications, and here additional parallelism was introcuded by
the blocktree itself
- understanding the inner working of Scorex, with almost no documentation, and being able to tune it to our needs

### Open Questions

While coming up with this solution several questions arose, that we haven't been able to answer yet:

- How often do the main chain switch-overs (when adding new blocks causes the chains to be reordered wrt their score) 
occur and how do they affect validity of transactions?
- If we implemented a bitcoin-like confirmation depth for transactions what effect would that have on the main 
chain switch-over frequency?
- How can nodes take advantage of the protocol to maximize their gains? What different block forging and transaction 
making (how to organize TXOs) strategies can they assume?
- How can dishonest nodes cheat the protocol, and what could we do to prevent that?

### What We Learned
We have:
- expanded our knowledge on the technical details of cryptocurrencies, we learned how Proof-of-Work and 
Proof-of-Stake work 
- learned that other proposals exist like Proof-of-Activity or Proof-of-Burn, and that we can mix those to create 
hybrid systems
- discovered new applications for blockchain technologies like digital identities, smart contracts, distributed storage
 or new opportunities for banking; we have built a keen interest on how those technologies will evolve
- we gained a considerable insight into how Scorex works, and came to a consensus about several possible improvements



