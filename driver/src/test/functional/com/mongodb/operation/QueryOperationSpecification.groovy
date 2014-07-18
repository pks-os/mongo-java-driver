/*
 * Copyright (c) 2008-2014 MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb.operation

import category.Async
import com.mongodb.MongoExecutionTimeoutException
import com.mongodb.ReadPreference
import com.mongodb.binding.ClusterBinding
import com.mongodb.codecs.DocumentCodec
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.junit.experimental.categories.Category
import org.mongodb.Block
import org.mongodb.Document
import org.mongodb.Fixture
import org.mongodb.FunctionalSpecification
import org.mongodb.Index

import static com.mongodb.operation.QueryFlag.Exhaust
import static java.util.Arrays.asList
import static java.util.concurrent.TimeUnit.SECONDS
import static org.junit.Assume.assumeFalse
import static org.junit.Assume.assumeTrue
import static org.mongodb.Fixture.disableMaxTimeFailPoint
import static org.mongodb.Fixture.enableMaxTimeFailPoint
import static org.mongodb.Fixture.getAsyncBinding
import static org.mongodb.Fixture.getBinding
import static org.mongodb.Fixture.getCluster
import static org.mongodb.Fixture.isSharded
import static org.mongodb.Fixture.serverVersionAtLeast

class QueryOperationSpecification extends FunctionalSpecification {

    def 'should query with no filter'() {
        def document = new Document()
        given:
        collection.insert(document)

        when:
        def cursor = new QueryOperation<Document>(getNamespace(), new Find().filter(null), new DocumentCodec()).execute(getBinding())

        then:
        cursor.next() == document
    }

    def 'should throw execution timeout exception from execute'() {
        assumeFalse(isSharded())
        assumeTrue(serverVersionAtLeast(asList(2, 5, 3)))

        given:
        collection.insert(new Document())
        def find = new Find().maxTime(1, SECONDS)
        def queryOperation = new QueryOperation<Document>(getNamespace(), find, new DocumentCodec())
        enableMaxTimeFailPoint()

        when:
        queryOperation.execute(getBinding())

        then:
        thrown(MongoExecutionTimeoutException)

        cleanup:
        disableMaxTimeFailPoint()
    }

    @Category(Async)
    def 'should throw execution timeout exception from executeAsync'() {
        assumeFalse(isSharded())
        assumeTrue(serverVersionAtLeast(asList(2, 5, 3)))

        given:
        collection.insert(new Document())
        def find = new Find().maxTime(1, SECONDS)
        def queryOperation = new QueryOperation<Document>(getNamespace(), find, new DocumentCodec())
        enableMaxTimeFailPoint()

        when:
        queryOperation.executeAsync(getAsyncBinding()).get();

        then:
        thrown(MongoExecutionTimeoutException)

        cleanup:
        disableMaxTimeFailPoint()
    }

    def '$max should limit items returned'() {
        given:
        for (
                i in
                        1..100) {
            collection.insert(new Document('x', 'y').append('count', i))
        }
        collection.tools().createIndexes(asList(Index.builder().addKey('count').build()))
        def count = 0;
        def find = new Find()
        find.getOptions().max(new BsonDocument('count', new BsonInt32(11)))
        def queryOperation = new QueryOperation<Document>(getNamespace(), find, new DocumentCodec())
        when:
        queryOperation.execute(getBinding()).each {
            count++
        }

        then:
        count == 10
    }

    def '$min should limit items returned'() {
        given:
        for (
                i in
                        1..100) {
            collection.insert(new Document('x', 'y').append('count', i))
        }
        collection.tools().createIndexes(asList(Index.builder().addKey('count').build()))
        def count = 0;
        def find = new Find()
        find.getOptions().min(new BsonDocument('count', new BsonInt32(10)))
        def queryOperation = new QueryOperation<Document>(getNamespace(), find, new DocumentCodec())
        when:
        queryOperation.execute(getBinding()).each {
            count++
        }

        then:
        count == 91
    }

    def '$maxScan should limit items returned'() {
        given:
        for (
                i in
                        1..100) {
            collection.insert(new Document('x', 'y'))
        }
        def count = 0;
        def find = new Find()
        find.getOptions().maxScan(34)
        def queryOperation = new QueryOperation<Document>(getNamespace(), find, new DocumentCodec())
        when:
        queryOperation.execute(getBinding()).each {
            count++
        }

        then:
        count == 34
    }

    def '$returnKey should only return the field that was in an index used to perform the find'() {
        given:
        for (
                i in
                        1..13) {
            collection.insert(new Document('x', i))
        }
        collection.tools().createIndexes([Index.builder().addKey('x').build()])

        def find = new Find(new BsonDocument('x', new BsonInt32(7)))
        find.getOptions().returnKey()
        def queryOperation = new QueryOperation<Document>(getNamespace(), find, new DocumentCodec())

        when:
        def cursor = queryOperation.execute(getBinding())

        then:
        def foundItem = cursor.next()
        foundItem.keySet().size() == 1
        foundItem['x'] == 7
    }

    def '$showDiskLoc should return disk locations'() {
        given:
        for (
                i in
                        1..100) {
            collection.insert(new Document('x', 'y'))
        }
        def found = true;
        def find = new Find()
        find.getOptions().showDiskLoc()
        def queryOperation = new QueryOperation<Document>(getNamespace(), find, new DocumentCodec())
        when:
        queryOperation.execute(getBinding()).each {
            found &= it['$diskLoc'] != null
        }

        then:
        found
    }

    def 'should read from a secondary'() {
        assumeTrue(Fixture.isDiscoverableReplicaSet())
        collection.insert(new Document())
        def find = new Find()
        def queryOperation = new QueryOperation<Document>(getNamespace(), find, new DocumentCodec())
        def binding = new ClusterBinding(getCluster(), ReadPreference.secondary(), 1, SECONDS)

        expect:
        queryOperation.execute(binding) != null // if it didn't throw, the query was executed
    }

    def 'should exhaust'() {
        assumeFalse(isSharded())

        for (
                i in
                        1..500) {
            collection.insert(new Document('_id', i))
        }
        def queryOperation = new QueryOperation<Document>(getNamespace(), new Find().addFlags(EnumSet.of(Exhaust)),
                                                          new DocumentCodec())

        when:
        def count = 0;

        def cursor = queryOperation.execute(getBinding())
        try {
            while (cursor.hasNext()) {
                cursor.next();
                count++;
            }
        } finally {
            cursor.close()
        }

        then:
        count == 500
    }

    @Category(Async)
    def 'should iterate asynchronously'() {
        assumeFalse(isSharded())

        for (
                i in
                        1..500) {
            collection.insert(new Document('_id', i))
        }
        def queryOperation = new QueryOperation<Document>(getNamespace(), new Find(), new DocumentCodec())

        when:
        def count = 0;

        def cursor = queryOperation.executeAsync(getAsyncBinding())
        cursor.get().forEach(new Block<Document>() {
            @Override
            void apply(final Document document) {
                count++;
            }
        }).get()

        then:
        count == 500
    }

    @Category(Async)
    def 'should exhaust asynchronously'() {
        assumeFalse(isSharded())

        for (
                i in
                        1..500) {
            collection.insert(new Document('_id', i))
        }
        def queryOperation = new QueryOperation<Document>(getNamespace(), new Find().addFlags(EnumSet.of(Exhaust)),
                                                          new DocumentCodec())

        when:
        def count = 0;

        def cursor = queryOperation.executeAsync(getAsyncBinding())
        cursor.get().forEach(new Block<Document>() {
            @Override
            void apply(final Document document) {
                count++;
            }
        }).get()

        then:
        count == 500
    }

}