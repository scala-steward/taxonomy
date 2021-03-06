/*
 * Copyright 2020 Michel Davit
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

package fr.davit.taxonomy.fs2

import cats.effect._
import cats.implicits._
import fr.davit.taxonomy.model.{DnsMessage, DnsPacket}
import fs2._
import fs2.io.udp.{Packet, Socket}
import scodec.Codec
import scodec.stream.{StreamDecoder, StreamEncoder}
import sun.net.dns.ResolverConfiguration

object Dns {

  def resolverConfiguration[F[_]: Sync]: Resource[F, ResolverConfiguration] =
    Resource.make(Sync[F].delay(ResolverConfiguration.open()))(_ => Sync[F].unit)

  def resolve[F[_]: Sync](
      socket: Socket[F],
      packet: DnsPacket
  )(implicit codec: Codec[DnsMessage]): F[DnsPacket] =
    for {
      data     <- Sync[F].delay(codec.encode(packet.message).require)
      _        <- socket.write(Packet(packet.address, Chunk.byteVector(data.toByteVector)))
      response <- socket.read()
      message  <- Sync[F].delay(codec.decode(response.bytes.toByteVector.toBitVector).require.value)
    } yield DnsPacket(response.remote, message)

  def stream[F[_]: RaiseThrowable](
      socket: Socket[F]
  )(implicit codec: Codec[DnsMessage]): Pipe[F, DnsPacket, Unit] = { input =>
    for {
      packet <- input
      _ <- Stream(packet.message)
        .through(StreamEncoder.once(codec).toPipeByte[F])
        .chunks
        .map(data => Packet(packet.address, data))
        .through(socket.writes())
    } yield ()
  }

  def listen[F[_]: RaiseThrowable](
      socket: Socket[F]
  )(implicit codec: Codec[DnsMessage]): Stream[F, DnsPacket] =
    for {
      datagram <- socket.reads()
      message <- Stream
        .chunk(datagram.bytes)
        .through(StreamDecoder.once(codec).toPipeByte[F])
    } yield DnsPacket(datagram.remote, message)
}
