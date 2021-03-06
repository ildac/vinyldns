/*
 * Copyright 2018 Comcast Cable Communications Management, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package vinyldns.mysql.repository

import java.util.UUID

import cats.effect._
import org.joda.time.DateTime
import org.scalatest._
import scalikejdbc.DB
import vinyldns.core.domain.record.{AAAAData, AData, RecordData, RecordType}
import vinyldns.core.domain.batch._
import vinyldns.core.TestZoneData.okZone
import vinyldns.core.TestMembershipData.okAuth
import vinyldns.mysql.TestMySqlInstance

class MySqlBatchChangeRepositoryIntegrationSpec
    extends WordSpec
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with Matchers
    with Inspectors
    with OptionValues {

  private var repo: BatchChangeRepository = _

  import SingleChangeStatus._
  import RecordType._

  object TestData {
    def generateSingleAddChange(recordType: RecordType, recordData: RecordData): SingleAddChange =
      SingleAddChange(okZone.id,
        okZone.name,
        "test",
        "test.somezone.com.",
        recordType, 3600,
        recordData,
        Pending,
        None,
        None,
        None)

    val sc1: SingleAddChange =
      generateSingleAddChange(A, AData("1.2.3.4"))

    val sc2: SingleAddChange =
      generateSingleAddChange(A, AData("1.2.3.40"))

    val sc3: SingleAddChange =
      generateSingleAddChange(AAAA, AAAAData("2001:558:feed:beef:0:0:0:1"))

    val deleteChange: SingleDeleteChange =
      SingleDeleteChange(
        okZone.id,
        okZone.name,
        "delete",
        "delete.somezone.com.",
        A,
        Pending,
        None,
        None,
        None)

    def randomBatchChange: BatchChange = BatchChange(
      okAuth.userId,
      okAuth.signedInUser.userName,
      Some("description"),
      DateTime.now,
      List(
        sc1.copy(id = UUID.randomUUID().toString),
        sc2.copy(id = UUID.randomUUID().toString),
        sc3.copy(id = UUID.randomUUID().toString),
        deleteChange.copy(id = UUID.randomUUID().toString)
      )
    )

    val bcARecords: BatchChange = randomBatchChange

    def randomBatchChangeWithList(singleChanges: List[SingleChange]): BatchChange =
      bcARecords.copy(id = UUID.randomUUID().toString, changes = singleChanges)

    val pendingBatchChange: BatchChange = randomBatchChange.copy(createdTimestamp = DateTime.now)

    val completeBatchChange: BatchChange = randomBatchChangeWithList(
      randomBatchChange.changes.map(_.complete("recordChangeId", "recordSetId")))
      .copy(createdTimestamp = DateTime.now.plusMillis(1000))

    val failedBatchChange: BatchChange =
      randomBatchChangeWithList(randomBatchChange.changes.map(_.withFailureMessage("failed")))
        .copy(createdTimestamp = DateTime.now.plusMillis(100000))

    val partialFailureBatchChange: BatchChange = randomBatchChangeWithList(
      randomBatchChange.changes.take(2).map(_.complete("recordChangeId", "recordSetId"))
        ++ randomBatchChange.changes.drop(2).map(_.withFailureMessage("failed"))
    ).copy(createdTimestamp = DateTime.now.plusMillis(1000000))
  }

  import TestData._

  override protected def beforeAll(): Unit =
    repo = TestMySqlInstance.batchChangeRepository

  override protected def beforeEach(): Unit =
    DB.localTx { s =>
      s.executeUpdate("DELETE FROM batch_change")
      s.executeUpdate("DELETE FROM single_change")
    }

  private def areSame(a: Option[BatchChange], e: Option[BatchChange]): Assertion = {
    a shouldBe defined
    e shouldBe defined

    val actual = a.get
    val expected = e.get

    areSame(actual, expected)
  }

  /* have to account for the database being different granularity than the JVM for DateTime */
  private def areSame(actual: BatchChange, expected: BatchChange): Assertion = {
    (actual.changes should contain).theSameElementsInOrderAs(expected.changes)
    actual.comments shouldBe expected.comments
    actual.id shouldBe expected.id
    actual.status shouldBe expected.status
    actual.userId shouldBe expected.userId
    actual.userName shouldBe expected.userId
    actual.createdTimestamp.getMillis shouldBe expected.createdTimestamp.getMillis +- 2000
  }

  private def areSame(actual: BatchChangeSummary, expected: BatchChangeSummary): Assertion = {
    actual.comments shouldBe expected.comments
    actual.id shouldBe expected.id
    actual.status shouldBe expected.status
    actual.userId shouldBe expected.userId
    actual.userName shouldBe expected.userId
    actual.createdTimestamp.getMillis shouldBe expected.createdTimestamp.getMillis +- 2000
  }

  private def areSame(
      actual: BatchChangeSummaryList,
      expected: BatchChangeSummaryList): Assertion = {
    forAll(actual.batchChanges.zip(expected.batchChanges)) { case (a, e) => areSame(a, e) }
    actual.batchChanges.length shouldBe expected.batchChanges.length
    actual.startFrom shouldBe expected.startFrom
    actual.nextId shouldBe expected.nextId
    actual.maxItems shouldBe expected.maxItems
  }

  "MySqlBatchChangeRepository" should {
    "save batch changes and single changes" in {
      repo.save(bcARecords).unsafeRunSync() shouldBe bcARecords
    }

    "get a batch change by ID" in {
      val f =
        for {
          _ <- repo.save(bcARecords)
          retrieved <- repo.getBatchChange(bcARecords.id)
        } yield retrieved

      areSame(f.unsafeRunSync(), Some(bcARecords))
    }

    "return none if a batch change is not found by ID" in {
      repo.getBatchChange("doesnotexist").unsafeRunSync() shouldBe empty
    }

    "get single changes by list of ID" in {
      val f =
        for {
          _ <- repo.save(bcARecords)
          retrieved <- repo.getSingleChanges(bcARecords.changes.map(_.id))
        } yield retrieved

      f.unsafeRunSync() shouldBe bcARecords.changes
    }

    "not fail on get empty list of single changes" in {
      val f = repo.getSingleChanges(List())

      f.unsafeRunSync() shouldBe List()
    }

    "get single changes should match order from batch changes" in {
      val batchChange = randomBatchChange
      val f =
        for {
          _ <- repo.save(batchChange)
          retrieved <- repo.getBatchChange(batchChange.id)
          singleChanges <- retrieved
            .map { r =>
              repo.getSingleChanges(r.changes.map(_.id).reverse)
            }
            .getOrElse(IO.pure[List[SingleChange]](Nil))
        } yield (retrieved, singleChanges)

      val (maybeBatchChange, singleChanges) = f.unsafeRunSync()
      maybeBatchChange.value.changes shouldBe singleChanges
    }

    "update single changes" in {
      val batchChange = randomBatchChange
      val completed = batchChange.changes.map(_.complete("aaa", "bbb"))
      val f =
        for {
          _ <- repo.save(batchChange)
          _ <- repo.updateSingleChanges(completed)
          retrieved <- repo.getSingleChanges(completed.map(_.id))
        } yield retrieved

      f.unsafeRunSync() shouldBe completed
    }

    "not fail on empty update single changes" in {
      val f = repo.updateSingleChanges(List())

      f.unsafeRunSync() shouldBe List()
    }

    "update some changes in a batch" in {
      val batchChange = randomBatchChange
      val completed = batchChange.changes.take(2).map(_.complete("recordChangeId", "recordSetId"))
      val incomplete = batchChange.changes.drop(2)
      val f =
        for {
          _ <- repo.save(batchChange)
          _ <- repo.updateSingleChanges(completed)
          retrieved <- repo.getSingleChanges(batchChange.changes.map(_.id))
        } yield retrieved

      f.unsafeRunSync() shouldBe completed ++ incomplete
    }

    "get batch change summary by user ID" in {
      val change_one = pendingBatchChange.copy(createdTimestamp = DateTime.now)
      val change_two = completeBatchChange.copy(createdTimestamp = DateTime.now.plusMillis(1000))
      val otherUserBatchChange =
        randomBatchChange.copy(userId = "Other", createdTimestamp = DateTime.now.plusMillis(50000))
      val change_three = failedBatchChange.copy(createdTimestamp = DateTime.now.plusMillis(100000))
      val change_four =
        partialFailureBatchChange.copy(createdTimestamp = DateTime.now.plusMillis(1000000))

      val f =
        for {
          _ <- repo.save(change_one)
          _ <- repo.save(change_two)
          _ <- repo.save(change_three)
          _ <- repo.save(change_four)
          _ <- repo.save(otherUserBatchChange)

          retrieved <- repo.getBatchChangeSummariesByUserId(pendingBatchChange.userId)
        } yield retrieved

      // from most recent descending
      val expectedChanges = BatchChangeSummaryList(
        List(
          BatchChangeSummary(change_four),
          BatchChangeSummary(change_three),
          BatchChangeSummary(change_two),
          BatchChangeSummary(change_one))
      )

      areSame(f.unsafeRunSync(), expectedChanges)
    }

    "get batch change summary by user ID with maxItems" in {
      val change_one = pendingBatchChange.copy(createdTimestamp = DateTime.now)
      val change_two = completeBatchChange.copy(createdTimestamp = DateTime.now.plusMillis(1000))
      val otherUserBatchChange =
        randomBatchChange.copy(userId = "Other", createdTimestamp = DateTime.now.plusMillis(50000))
      val change_three = failedBatchChange.copy(createdTimestamp = DateTime.now.plusMillis(100000))
      val change_four =
        partialFailureBatchChange.copy(createdTimestamp = DateTime.now.plusMillis(1000000))

      val f =
        for {
          _ <- repo.save(change_one)
          _ <- repo.save(change_two)
          _ <- repo.save(change_three)
          _ <- repo.save(change_four)
          _ <- repo.save(otherUserBatchChange)

          retrieved <- repo.getBatchChangeSummariesByUserId(pendingBatchChange.userId, maxItems = 3)
        } yield retrieved

      // from most recent descending
      val expectedChanges = BatchChangeSummaryList(
        List(
          BatchChangeSummary(change_four),
          BatchChangeSummary(change_three),
          BatchChangeSummary(change_two)),
        None,
        Some(3),
        3
      )

      areSame(f.unsafeRunSync(), expectedChanges)
    }

    "get batch change summary by user ID with explicit startFrom" in {
      val timeBase = DateTime.now
      val change_one = pendingBatchChange.copy(createdTimestamp = timeBase)
      val change_two = completeBatchChange.copy(createdTimestamp = timeBase.plus(1000))
      val otherUserBatchChange =
        randomBatchChange.copy(userId = "Other", createdTimestamp = timeBase.plus(50000))
      val change_three = failedBatchChange.copy(createdTimestamp = timeBase.plus(100000))
      val change_four = partialFailureBatchChange.copy(createdTimestamp = timeBase.plus(1000000))

      val f =
        for {
          _ <- repo.save(change_one)
          _ <- repo.save(change_two)
          _ <- repo.save(change_three)
          _ <- repo.save(change_four)
          _ <- repo.save(otherUserBatchChange)

          retrieved <- repo.getBatchChangeSummariesByUserId(
            pendingBatchChange.userId,
            startFrom = Some(1),
            maxItems = 3)
        } yield retrieved

      // sorted from most recent descending. startFrom uses zero-based indexing.
      // Expect to get only the second batch change, change_3.
      // No nextId because the maxItems (3) equals the number of batch changes the user has after the offset (3)
      val expectedChanges = BatchChangeSummaryList(
        List(
          BatchChangeSummary(change_three),
          BatchChangeSummary(change_two),
          BatchChangeSummary(change_one)),
        Some(1),
        None,
        3
      )

      areSame(f.unsafeRunSync(), expectedChanges)
    }

    "get batch change summary by user ID with explicit startFrom and maxItems" in {
      val timeBase = DateTime.now
      val change_one = pendingBatchChange.copy(createdTimestamp = timeBase)
      val change_two = completeBatchChange.copy(createdTimestamp = timeBase.plus(1000))
      val otherUserBatchChange =
        randomBatchChange.copy(userId = "Other", createdTimestamp = timeBase.plus(50000))
      val change_three = failedBatchChange.copy(createdTimestamp = timeBase.plus(100000))
      val change_four = partialFailureBatchChange.copy(createdTimestamp = timeBase.plus(1000000))

      val f =
        for {
          _ <- repo.save(change_one)
          _ <- repo.save(change_two)
          _ <- repo.save(change_three)
          _ <- repo.save(change_four)
          _ <- repo.save(otherUserBatchChange)

          retrieved <- repo.getBatchChangeSummariesByUserId(
            pendingBatchChange.userId,
            startFrom = Some(1),
            maxItems = 1)
        } yield retrieved

      // sorted from most recent descending. startFrom uses zero-based indexing.
      // Expect to get only the second batch change, change_3.
      // Expect the ID of the next batch change to be 2.
      val expectedChanges =
        BatchChangeSummaryList(List(BatchChangeSummary(change_three)), Some(1), Some(2), 1)

      areSame(f.unsafeRunSync(), expectedChanges)
    }

    "get second page of batch change summaries by user ID" in {
      val timeBase = DateTime.now
      val change_one = pendingBatchChange.copy(createdTimestamp = timeBase)
      val change_two = completeBatchChange.copy(createdTimestamp = timeBase.plus(1000))
      val otherUserBatchChange =
        randomBatchChange.copy(userId = "Other", createdTimestamp = timeBase.plus(50000))
      val change_three = failedBatchChange.copy(createdTimestamp = timeBase.plus(100000))
      val change_four = partialFailureBatchChange.copy(createdTimestamp = timeBase.plus(1000000))

      val f =
        for {
          _ <- repo.save(change_one)
          _ <- repo.save(change_two)
          _ <- repo.save(change_three)
          _ <- repo.save(change_four)
          _ <- repo.save(otherUserBatchChange)

          retrieved1 <- repo.getBatchChangeSummariesByUserId(
            pendingBatchChange.userId,
            maxItems = 1)
          retrieved2 <- repo.getBatchChangeSummariesByUserId(
            pendingBatchChange.userId,
            startFrom = retrieved1.nextId)
        } yield (retrieved1, retrieved2)

      val expectedChanges =
        BatchChangeSummaryList(List(BatchChangeSummary(change_four)), None, Some(1), 1)

      val secondPageExpectedChanges = BatchChangeSummaryList(
        List(
          BatchChangeSummary(change_three),
          BatchChangeSummary(change_two),
          BatchChangeSummary(change_one)),
        Some(1),
        None
      )
      val retrieved = f.unsafeRunSync()
      areSame(retrieved._1, expectedChanges)
      areSame(retrieved._2, secondPageExpectedChanges)
    }

    "return empty list if a batch change summary is not found by user ID" in {
      val batchChangeSummaries = repo.getBatchChangeSummariesByUserId("doesnotexist").unsafeRunSync()
      batchChangeSummaries.batchChanges shouldBe empty
    }
  }
}
