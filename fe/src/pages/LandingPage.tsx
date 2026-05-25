import React, { useRef } from 'react';
import { motion, useScroll, useTransform, MotionValue } from 'framer-motion';
import { ChevronDown } from 'lucide-react';
import { Link } from 'react-router-dom';

export default function LandingPage() {
  const containerRef = useRef<HTMLDivElement>(null);

  // Parallax Scroll Effects
  const { scrollYProgress } = useScroll({
    target: containerRef,
    offset: ["start start", "end start"]
  });

  const heroTextY = useTransform(scrollYProgress, [0, 1], [0, -400]);
  const heroTextOpacity = useTransform(scrollYProgress, [0, 0.5], [1, 0]);
  const dashboardY = useTransform(scrollYProgress, [0, 1], [0, -250]);

  return (
    <div className="min-h-screen bg-background text-foreground overflow-x-hidden selection:bg-white/20" ref={containerRef}>
      {/* Navbar */}
      <nav className="fixed top-0 left-0 right-0 z-50 flex items-center justify-between px-8 md:px-28 py-4 bg-background/50 backdrop-blur-md border-b border-white/5">
        <div className="flex items-center gap-12 md:gap-20">
          <div className="flex items-center gap-3">
            <img src="/logo.png" alt="Neuralyn Logo" className="w-8 h-8 rounded-md" />
            <span className="text-xl font-bold tracking-tight">Neuralyn</span>
          </div>
          <div className="hidden md:flex items-center gap-1 text-sm font-medium text-muted-foreground">
            <a href="#" className="px-3 py-2 hover:text-foreground transition-colors">Home</a>
            <a href="#" className="px-3 py-2 hover:text-foreground transition-colors flex items-center gap-1">
              Services <ChevronDown className="w-4 h-4" />
            </a>
            <a href="#" className="px-3 py-2 hover:text-foreground transition-colors">Reviews</a>
            <a href="#" className="px-3 py-2 hover:text-foreground transition-colors">Contact us</a>
          </div>
        </div>
        <Link to="/login" className="bg-foreground text-background px-4 py-2 rounded-lg text-sm font-semibold hover:opacity-90 transition-opacity">
          Sign In
        </Link>
      </nav>

      {/* Hero Section */}
      <section className="relative pt-32 pb-20 md:pt-40 md:pb-32 flex flex-col items-center">
        <motion.div 
          className="flex flex-col items-center text-center px-4 z-20"
          style={{ y: heroTextY, opacity: heroTextOpacity }}
        >
          {/* Liquid Glass Pill */}
          <motion.div 
            initial={{ opacity: 0, y: 10 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.5, delay: 0 }}
            className="liquid-glass px-3 py-2 rounded-lg mb-6 flex items-center gap-2"
          >
            <span className="bg-white text-black rounded-md text-xs font-bold px-2 py-0.5">IVDR</span>
            <span className="text-sm font-medium text-muted-foreground pr-1">Intelligent Medical Device Compliance</span>
          </motion.div>

          {/* Title */}
          <motion.h1 
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.6, delay: 0.1 }}
            className="text-5xl md:text-7xl tracking-[-2px] font-medium leading-tight md:leading-[1.15] mb-4 max-w-4xl"
          >
            Compliance Documentation. <br className="md:hidden" />
            <span className="font-serif italic font-normal text-white">One Clear Overview.</span>
          </motion.h1>

          {/* Subtitle */}
          <motion.p 
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.6, delay: 0.2 }}
            className="text-lg font-normal leading-6 opacity-90 mb-8 max-w-xl text-[hsl(var(--hero-subtitle))]"
          >
            IVDR Portal helps medical device manufacturers track compliance,<br className="hidden md:block"/> analyze documentation with AI, and manage secure workspaces.
          </motion.p>

          {/* CTA */}
          <Link to="/register">
            <motion.button 
              initial={{ opacity: 0, y: 20 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.6, delay: 0.3 }}
              whileHover={{ scale: 1.03 }}
              whileTap={{ scale: 0.98 }}
              className="bg-foreground text-background rounded-full px-8 py-3.5 text-base font-medium shadow-xl shadow-white/10"
            >
              Get Started for Free
            </motion.button>
          </Link>
        </motion.div>

        {/* Dashboard + Video Area */}
        <motion.div 
          initial={{ opacity: 0, y: 40 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.8, delay: 0.4 }}
          className="relative w-screen aspect-video mt-20"
          style={{ marginLeft: 'calc(-50vw + 50%)', y: dashboardY }}
        >
          <video 
            autoPlay 
            loop 
            muted 
            playsInline
            className="absolute inset-0 w-full h-full object-cover opacity-60 mix-blend-screen"
            src="https://d8j0ntlcm91z4.cloudfront.net/user_38xzZboKViGWJOttwIXH07lWA1P/hf_20260307_083826_e938b29f-a43a-41ec-a153-3d4730578ab8.mp4"
          />
          <img 
            src="/hero-dashboard.png" 
            alt="Dashboard Preview" 
            className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[90%] max-w-5xl rounded-2xl border border-white/10 shadow-2xl mix-blend-luminosity"
          />
          <div className="absolute bottom-0 left-0 right-0 h-40 bg-gradient-to-t from-background to-transparent z-30 pointer-events-none" />
        </motion.div>
      </section>

      {/* Testimonial Section */}
      <TestimonialSection />
    </div>
  );
}

function Word({ children, progress, range }: { children: React.ReactNode, progress: MotionValue<number>, range: [number, number] }) {
  const opacity = useTransform(progress, range, [0.2, 1]);
  const color = useTransform(progress, range, ["hsl(0 0% 35%)", "hsl(0 0% 100%)"]);
  return (
    <motion.span style={{ opacity, color }} className="mr-[0.3em] inline-block">
      {children}
    </motion.span>
  );
}

function TestimonialSection() {
  const text = "The IVDR Portal revolutionized how we handle medical device documentation using smart AI analytics. We are now achieving compliance quicker than we ever imagined! The IVDR Portal revolutionized how we handle medical device documentation using smart AI analytics.";
  const words = text.split(" ");
  
  const containerRef = useRef<HTMLDivElement>(null);
  const { scrollYProgress } = useScroll({
    target: containerRef,
    offset: ["start end", "end center"]
  });

  return (
    <section ref={containerRef} className="min-h-screen flex flex-col items-center py-24 md:py-32 px-8 md:px-28 relative z-40 bg-background">
      <div className="max-w-3xl w-full flex flex-col items-start gap-10">
        <img src="/quote-symbol.png" alt="Quote" className="w-14 h-10 object-contain opacity-80" />
        
        <p className="text-4xl md:text-5xl font-medium leading-[1.2] flex flex-wrap">
          {words.map((word, i) => {
            const start = i / words.length;
            const end = start + (1 / words.length);
            return <Word key={i} progress={scrollYProgress} range={[start, end]}>{word}</Word>;
          })}
          <span className="text-muted-foreground ml-2">"</span>
        </p>
        
        <div className="flex items-center gap-4 mt-4">
          <img 
            src="/testimonial-avatar.png" 
            alt="Brooklyn Simmons" 
            className="w-14 h-14 rounded-full border-[3px] border-foreground object-cover"
          />
          <div className="flex flex-col">
            <span className="text-base font-semibold leading-7 text-foreground">Brooklyn Simmons</span>
            <span className="text-sm font-normal leading-5 text-muted-foreground">Product Manager</span>
          </div>
        </div>
      </div>
    </section>
  );
}
