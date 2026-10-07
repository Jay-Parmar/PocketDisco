import React, { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';

import { Button } from './components';
import { colors } from './theme';
import type { RoomMember } from './types';

export function MembersCard({
  members,
  currentUserId,
}: {
  members: readonly RoomMember[];
  currentUserId: string;
}) {
  const [expanded, setExpanded] = useState(false);
  const visibleMembers = expanded ? members : members.slice(0, 4);
  const readyCount = members.filter(
    member => member.ready && member.connected,
  ).length;

  return (
    <View style={styles.card}>
      <View style={styles.heading}>
        <Text accessibilityRole="header" style={styles.title}>
          Your people
        </Text>
        <View style={styles.countBadge}>
          <Text style={styles.count}>{members.length}/25</Text>
        </View>
      </View>
      <Text style={styles.subtitle}>
        {readyCount} of {members.length}{' '}
        {members.length === 1 ? 'person' : 'people'} ready
      </Text>
      <View style={styles.members}>
        {visibleMembers.map((member, index) => {
          const isYou = member.user_id === currentUserId;
          const status = !member.connected
            ? 'Away'
            : member.ready
            ? 'Ready'
            : 'Here';
          const initial =
            Array.from(member.display_name.trim())[0]?.toLocaleUpperCase() ||
            '?';
          return (
            <View
              key={member.user_id}
              testID={`member-${member.user_id}`}
              accessible
              accessibilityLabel={`${member.display_name}${
                isYou ? ', you' : ''
              }, ${member.role}, ${status}`}
              style={styles.member}
            >
              <View
                accessibilityElementsHidden
                importantForAccessibility="no-hide-descendants"
                style={[
                  styles.avatar,
                  index % 3 === 1 && styles.coralAvatar,
                  index % 3 === 2 && styles.creamAvatar,
                ]}
              >
                <Text style={styles.initial}>{initial}</Text>
              </View>
              <View style={styles.memberInfo}>
                <Text style={styles.memberName}>
                  {member.display_name}
                  {isYou && <Text style={styles.you}> (you)</Text>}
                </Text>
                <Text style={styles.role}>
                  {member.role === 'host' ? 'Host' : 'Room member'}
                </Text>
              </View>
              <Text
                style={[
                  styles.memberStatus,
                  member.ready && member.connected && styles.readyStatus,
                ]}
              >
                {status}
              </Text>
            </View>
          );
        })}
      </View>
      {members.length > 4 && (
        <Button
          label={
            expanded ? 'Show fewer people' : `See all ${members.length} people`
          }
          tone="quiet"
          testID="expand-members-button"
          onPress={() => setExpanded(!expanded)}
        />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    backgroundColor: colors.paper,
    borderRadius: 24,
    borderWidth: 1,
    borderColor: colors.line,
    padding: 20,
    gap: 8,
  },
  heading: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  title: {
    fontSize: 21,
    fontWeight: '700',
    color: colors.ink,
    flex: 1,
    letterSpacing: -0.6,
  },
  countBadge: {
    paddingHorizontal: 9,
    paddingVertical: 5,
    borderRadius: 10,
    backgroundColor: colors.background,
  },
  count: { fontSize: 12, fontWeight: '700', color: colors.muted },
  subtitle: { fontSize: 13, color: colors.muted, lineHeight: 20 },
  members: { marginTop: 12, gap: 19 },
  member: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  avatar: {
    width: 44,
    height: 44,
    borderRadius: 22,
    backgroundColor: colors.lime,
    alignItems: 'center',
    justifyContent: 'center',
  },
  coralAvatar: { backgroundColor: '#FFD5C4' },
  creamAvatar: { backgroundColor: '#E7E9D7' },
  initial: { fontSize: 17, fontWeight: '700', color: colors.ink },
  memberInfo: { flex: 1, gap: 3 },
  memberName: {
    fontSize: 15,
    fontWeight: '700',
    color: colors.ink,
    lineHeight: 21,
  },
  you: { fontWeight: '400', color: colors.muted },
  role: { fontSize: 12, lineHeight: 18, color: colors.muted },
  memberStatus: {
    fontSize: 12,
    color: colors.muted,
    fontWeight: '600',
    flexShrink: 1,
  },
  readyStatus: { color: colors.green },
});
