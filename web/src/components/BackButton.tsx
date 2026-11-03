// Back, named after where it goes ("‹ Spaces"), so it is never a guess.
import { ChevronLeft } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import { back, upLabel } from '@/route';

export function BackButton({ always = false, label = upLabel() }: { always?: boolean; label?: string }) {
  return (
    <Button variant="ghost" size="sm" onClick={back} aria-label={`Back to ${label}`}
      className={cn('-ml-1 h-9 shrink-0 gap-0 px-1 pr-2 text-[13px] font-normal text-muted-foreground', !always && 'lg:hidden')}>
      <ChevronLeft className="size-5" />{label}
    </Button>
  );
}
