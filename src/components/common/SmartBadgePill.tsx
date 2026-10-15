import React from 'react';
import { MediaStatus, MediaType } from '../../types';
import { getStatusBadge, getTypeBadge } from '../../utils/badgeEngine';
import { resolveNetwork } from '../../utils/networkResolver';
import { Film, Tv, Sparkles, Gamepad2, BookOpen } from 'lucide-react';

interface SmartBadgePillProps {
  status?: MediaStatus;
  type?: MediaType;
  network?: string;
  size?: 'sm' | 'md';
  customLabel?: string;
  className?: string;
}

export const SmartBadgePill: React.FC<SmartBadgePillProps> = ({
  status,
  type,
  network,
  size = 'md',
  customLabel,
  className = '',
}) => {
  const sizeClasses = size === 'sm' ? 'text-xs px-2 py-0.5' : 'text-xs px-2.5 py-1 font-medium';

  if (status) {
    const meta = getStatusBadge(status);
    return (
      <span
        className={`inline-flex items-center gap-1.5 rounded-full border ${meta.bg} ${meta.text} ${meta.border} ${sizeClasses} ${className}`}
      >
        <span className={`w-1.5 h-1.5 rounded-full ${meta.dot} animate-pulse`} />
        {customLabel || meta.label}
      </span>
    );
  }

  if (type) {
    const meta = getTypeBadge(type);
    const renderIcon = () => {
      const iconSize = size === 'sm' ? 11 : 13;
      switch (type) {
        case 'movie':
          return <Film size={iconSize} />;
        case 'tv':
          return <Tv size={iconSize} />;
        case 'anime':
          return <Sparkles size={iconSize} />;
        case 'game':
          return <Gamepad2 size={iconSize} />;
        case 'book':
          return <BookOpen size={iconSize} />;
      }
    };

    return (
      <span
        className={`inline-flex items-center gap-1.5 rounded-md border ${meta.color} ${sizeClasses} ${className}`}
      >
        {renderIcon()}
        <span>{customLabel || meta.label}</span>
      </span>
    );
  }

  if (network) {
    const meta = resolveNetwork(network);
    return (
      <span
        className={`inline-flex items-center gap-1 rounded-md border ${meta.badgeClass} ${sizeClasses} ${className}`}
      >
        <span>{customLabel || meta.name}</span>
      </span>
    );
  }

  return null;
};
