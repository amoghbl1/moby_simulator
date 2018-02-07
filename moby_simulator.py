#!/usr/bin/env python
from collections import defaultdict
import argparse
import sys

DATA_FILE_PREFIX = "data/"
DATA_FILE_FORMAT = ".twr"
MESSAGE_FILE_FORMAT = ".msg"

network_state_old = defaultdict(set)
network_state_new = defaultdict(set)
message_queue = defaultdict(dict)
message_delivered = defaultdict(list)
message_delivery_count = 0
dirty_nodes = []

# CLI args
start_day = 0
end_day = 3
city_number = 0
cool_down = 12

class Message:
    def __init__(self, id, ttl, src, dst, hop, trust):
        self.id  = id
        self.ttl = ttl
        self.src = src
        self.dst = dst
        self.hop = hop
        self.trust = trust

def main():
    parser = argparse.ArgumentParser(description='Moby simulation script.')
    parser.add_argument('--start_day', help='start day of the year', type=int, nargs='?', default=0)
    parser.add_argument('--end_day', help='end day of the year', type=int, nargs='?', default=3)
    parser.add_argument('--city_number', help='city to run test for', type=int, nargs='?', default=0)
    parser.add_argument('--cool_down', help='cool down hours', type=int, nargs='?', default=12)
    args = parser.parse_args(sys.argv[1:])
    start_day = args.start_day
    end_day = args.end_day
    city_number = args.city_number
    cool_down = args.cool_down
    print start_day, end_day, city_number, cool_down
    first_state = True
    total_messages = 0
    global dirty_nodes
    global message_delivery_count
    global network_state_new
    global network_state_old
    read_message_counter = (end_day - start_day) * 24
    read_message_counter -= cool_down
    read_message_counter += 1
    for current_day in xrange(start_day, end_day):
        for current_hour in xrange(0,24):
            read_message_counter -= 1
            dirty_nodes = []
            network_state_new = defaultdict(set)
            current_data_file = DATA_FILE_PREFIX + str(city_number) + "/" + str(current_day) + "_" + str(current_hour) + DATA_FILE_FORMAT
            current_message_file = DATA_FILE_PREFIX + str(city_number) + "/" + str(current_day) + "_" + str(current_hour) + MESSAGE_FILE_FORMAT
            users_this_hour = []
            print "Message Delivery count: ", message_delivery_count, " of: ", total_messages
            if total_messages > 0:
                print "Delivery rate: ", (float(message_delivery_count) / total_messages)*100, "%"
            print "Processing hour: ", current_hour, " File: ", current_data_file
            with open(current_data_file) as data:
                for entry in data:
                    # Just calcuate this hours state, no modification to last hour.
                    # All modifications and related logic at the end of the hour.
                    hour, tower_id, user_ids = entry.split(",")
                    user_ids = user_ids.strip()
                    users_this_hour += user_ids.split("|")
                    current_state = set(user_ids.split("|"))
                    network_state_new[tower_id] = current_state
                """
                previous_state = network_state[tower_id]
                transitions_added = new_state.difference(previous_state)
                transitions_removed = previous_state.difference(new_state)
                transitions_idle = new_state.intersection(previous_state)
                """
                # print "Added: ", transitions_added
                # print "Removed: ", transitions_removed
            print "Users in all towers(double counted):", len(users_this_hour)
            print "Users seen this hour: ", len(set(users_this_hour))
            print "States updated. Sending a few messages around."
            # Parse the .msg file
            if read_message_counter > 0:
                print "Parsing: ", current_message_file
                with open(current_message_file) as data:
                    for entry in data:
                        # ID, TTL, Source, Destination, hop, trust
                        id, ttl, src, dst, hop, trust = entry.strip().split(",")
                        msg = Message(id, int(ttl), src, dst, hop, trust)
                        message_queue[src][msg.id] = msg
                        dirty_nodes.append(src)
                        total_messages += 1
            changes_added = changes_removed = []
            if not first_state:
                for tower in network_state_new.keys():
                    # Nodes that are new to a tower need to trigger msg exchanges, ignore this for first run
                    # as the first run marks all of them as new!
                    transitions_added = network_state_new[tower].difference(network_state_old[tower])
                    transitions_removed = network_state_old[tower].difference(network_state_new[tower])
                    changes_added += transitions_added
                    changes_removed += transitions_removed
                    dirty_nodes += transitions_added
            print "Network changes added: ", len(changes_added), " removed: ", len(changes_removed)
            print "Total dirty nodes: ", len(dirty_nodes)
            first_state = False
            print "Simulating message exchange on all nodes that belong to a network."
            for tower in network_state_new.keys():
                users_in_tower = network_state_new[tower]
                if perform_message_exchanges(users_in_tower):
                    dirty_nodes += users_in_tower
            print "Messages for this hour sent, message exchanges complete. Perform destination check"
            for user, mq in message_queue.iteritems():
                dellist = []
                for key, msg in mq.iteritems():
                    # print "Dst: ", msg.dst, "User:", user
                    if msg.id not in message_delivered[user]:
                        if msg.dst == user and msg.id:
                            message_delivery_count += 1
                            message_delivered[user].append(msg.id)
                            msg.ttl = 60
                        elif msg.ttl > 1:
                            msg.ttl -= 1
                        else:
                            dellist.append(msg.id)
                for id in dellist:
                    del message_queue[user][id]
            print "Updaing old state to new state."
            for key, value in network_state_new.iteritems():
                network_state_old[key] = value

def clean_users(users):
    global dirty_nodes
    new_dirty = []
    for node in dirty_nodes:
        if node not in users:
            new_dirty.append(node)
    dirty_nodes = new_dirty

def perform_message_exchanges(users):
    dirty = list(set(users).intersection(dirty_nodes))
    for u1 in dirty:
        for u2 in users:
            if u1 == u2:
                continue
            mq1 = message_queue[u1]
            mq2 = message_queue[u2]
            for key in mq1.keys():
                mq2[key] = mq1[key]
            for key in mq2.keys():
                mq1[key] = mq2[key]
    return len(dirty) > 0
                    # Any of the trust score changing logic should come here.
    # print "Resulting message queue length: ", len(mq)

if __name__ == "__main__":
    main()
